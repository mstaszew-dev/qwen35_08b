package com.zcode.qwen35gw.gateway

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zcode.qwen35gw.model.RequestTransform
import com.zcode.qwen35gw.runtime.LlamaServerProcess
import com.zcode.qwen35gw.runtime.LlamaState
import com.zcode.qwen35gw.runtime.RequestTracker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class GatewayConfig(
    val listenPort: Int,
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int,
    val modelId: String = "qwen3.5-0.8b",
    val pruneBudget: Int = 30_000,
)

interface Upstream {
    fun chatCompletions(body: String): UpstreamResult
}

sealed class UpstreamResult {
    data class Success(
        val status: Int,
        val contentType: String?,
        val bodyBytes: ByteArray,
    ) : UpstreamResult()

    data class Failure(val message: String) : UpstreamResult()
}

class HttpUpstream(
    private val baseUrl: String,
    private val ready: (String) -> Unit = {},
) : Upstream {
    private val client: HttpClient = HttpClient.newBuilder().build()

    override fun chatCompletions(body: String): UpstreamResult {
        return try {
            val response = client.send(
                HttpRequest.newBuilder(URI(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val raw = response.body().use(::readFully)
            bodyModel(body)?.let(ready)
            UpstreamResult.Success(
                status = response.statusCode(),
                contentType = response.headers().firstValue("Content-Type").orElse(null),
                bodyBytes = raw,
            )
        } catch (e: Exception) {
            UpstreamResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun bodyModel(body: String): String? {
        return try {
            (Json.parseToJsonElement(body).jsonObject["model"] as? JsonPrimitive)?.content
        } catch (e: Exception) {
            null
        }
    }

    private fun readFully(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

class Gateway(
    private val cfg: GatewayConfig,
    private val upstream: Upstream,
    private val process: LlamaServerProcess,
    private val tracker: RequestTracker,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    fun start() {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", cfg.listenPort), 0)
        executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = false } }
        created.executor = executor
        created.createContext("/v1/chat/completions") { exchange -> handleChat(exchange) }
        created.createContext("/v1/models") { exchange ->
            send(exchange, 200, "application/json", modelsBody())
        }
        created.createContext("/healthz") { exchange ->
            send(exchange, 200, "application/json", healthBody())
        }
        server = created
        created.start()
    }

    fun stop() {
        server?.stop(0)
        executor?.shutdown()
    }

    private fun handleChat(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            send(exchange, 405, null, ByteArray(0))
            return
        }
        val request: JsonObject
        try {
            request = Json.parseToJsonElement(String(exchange.requestBody.readAllBytes())).jsonObject
        } catch (e: Exception) {
            send(exchange, 400, "application/json", errorBody("invalid request body", "bad_request"))
            return
        }
        val prepared = RequestTransform.prepare(request, cfg.modelId, cfg.pruneBudget)
        tracker.begin()
        var notLoaded = false
        try {
            if (process.state() != LlamaState.RUNNING && !process.start()) {
                notLoaded = true
            } else {
                relay(exchange, prepared)
            }
        } catch (e: Exception) {
            runCatching {
                send(exchange, 500, "application/json", errorBody("internal error", "internal_error"))
            }
        } finally {
            tracker.end()
        }
        if (notLoaded) {
            send(exchange, 503, "application/json", errorBody("model not loaded", "service_unavailable"))
        }
    }

    private fun relay(exchange: HttpExchange, prepared: JsonObject) {
        when (val result = upstream.chatCompletions(Json.encodeToString(prepared))) {
            is UpstreamResult.Success -> send(exchange, result.status, result.contentType, result.bodyBytes)
            is UpstreamResult.Failure -> send(
                exchange,
                502,
                "application/json",
                errorBody(result.message, "upstream_error"),
            )
        }
    }

    private fun healthBody(): ByteArray = buildJsonObject {
        put("status", "up")
        put("model", cfg.modelId)
        put("loaded", process.state() == LlamaState.RUNNING)
        put("active", tracker.active())
    }.toString().toByteArray()

    private fun modelsBody(): ByteArray = buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            add(buildJsonObject {
                put("id", cfg.modelId)
                put("object", "model")
                put("owned_by", "qwen35-gw")
            })
        })
    }.toString().toByteArray()

    private fun errorBody(message: String, type: String): ByteArray = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", type)
        })
    }.toString().toByteArray()

    private fun send(exchange: HttpExchange, status: Int, contentType: String?, body: ByteArray) {
        if (contentType != null) {
            exchange.responseHeaders.set("Content-Type", contentType)
        }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) {
            exchange.responseBody.write(body)
            exchange.responseBody.flush()
        }
        exchange.close()
    }
}