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
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val relayTimeout: Duration = Duration.ofMinutes(5),
) : Upstream {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    override fun chatCompletions(body: String): UpstreamResult {
        return try {
            val deadlineNanos = System.nanoTime() + relayTimeout.toNanos()
            val response = client.send(
                HttpRequest.newBuilder(URI(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(relayTimeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val raw = DeadlineInputStream(response.body(), deadlineNanos).use(::readFully)
            UpstreamResult.Success(
                status = response.statusCode(),
                contentType = response.headers().firstValue("Content-Type").orElse(null),
                bodyBytes = raw,
            )
        } catch (e: Exception) {
            UpstreamResult.Failure(e.message ?: e.javaClass.simpleName)
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

    private class DeadlineInputStream(
        private val delegate: InputStream,
        private val deadlineNanos: Long,
    ) : InputStream() {
        private fun checkDeadline() {
            if (Thread.currentThread().isInterrupted) {
                throw IOException("upstream relay interrupted")
            }
            if (System.nanoTime() > deadlineNanos) {
                throw IOException("upstream relay timed out")
            }
        }

        override fun read(): Int {
            checkDeadline()
            return delegate.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkDeadline()
            return delegate.read(b, off, len)
        }

        override fun close() {
            delegate.close()
        }
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

    companion object {
        private const val MAX_HANDLER_THREADS = 8
        private const val MAX_BODY_BYTES = 1 shl 20
    }

    fun start() {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", cfg.listenPort), 0)
        executor = Executors.newFixedThreadPool(MAX_HANDLER_THREADS) { r -> Thread(r).apply { isDaemon = false } }
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
        executor?.shutdownNow()
    }

    private fun handleChat(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            send(exchange, 405, null, ByteArray(0))
            return
        }
        val body = readBounded(exchange.requestBody, MAX_BODY_BYTES)
        if (body == null) {
            send(exchange, 413, "application/json", errorBody("request too large", "payload_too_large"))
            return
        }
        val request: JsonObject
        try {
            request = Json.parseToJsonElement(String(body)).jsonObject
        } catch (e: Exception) {
            send(exchange, 400, "application/json", errorBody("invalid request body", "bad_request"))
            return
        }
        tracker.begin()
        var notLoaded = false
        try {
            val prepared = RequestTransform.prepare(request, cfg.modelId, cfg.pruneBudget)
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

    private fun readBounded(input: InputStream, max: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (output.size() + read > max) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun relay(exchange: HttpExchange, prepared: JsonObject) {
        when (val result = upstream.chatCompletions(Json.encodeToString(prepared))) {
            is UpstreamResult.Success ->
                if (result.status in 500..599) {
                    send(
                        exchange,
                        502,
                        "application/json",
                        errorBody("upstream error (${result.status})", "upstream_error"),
                    )
                } else {
                    send(exchange, result.status, result.contentType, result.bodyBytes)
                }

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