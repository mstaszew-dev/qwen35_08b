package com.zcode.qwen35gw.gateway

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.zcode.qwen35gw.runtime.Clock
import com.zcode.qwen35gw.runtime.LlamaConfig
import com.zcode.qwen35gw.runtime.LlamaServerProcess
import com.zcode.qwen35gw.runtime.LlamaState
import com.zcode.qwen35gw.runtime.ManagedProcess
import com.zcode.qwen35gw.runtime.ManagedProcessLauncher
import com.zcode.qwen35gw.runtime.RequestTracker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class GatewayTest {

    @TempDir
    lateinit var tempDir: File

    private val http: HttpClient = HttpClient.newBuilder().build()

    private class FixedClock(private val now: Long = 1000L) : Clock {
        override fun nowMillis(): Long = now
    }

    private class FakeProcess : ManagedProcess {
        private var alive = true
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            alive = false
        }

        override fun waitFor(ms: Long): Boolean = !alive
    }

    private class FakeLauncher : ManagedProcessLauncher {
        override fun launch(cmd: List<String>, logFile: String): ManagedProcess = FakeProcess()
    }

    private data class StubResponse(val status: Int, val contentType: String?, val body: ByteArray)

    private class StubServer {
        val port: Int
        private val server: HttpServer

        @Volatile
        var healthy = false

        @Volatile
        var chatHandler: (String) -> StubResponse = { body ->
            StubResponse(200, "application/json", body.toByteArray())
        }

        val received = ConcurrentLinkedQueue<Pair<String, String>>()

        init {
            port = freePort()
            server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            server.executor = Executors.newCachedThreadPool()
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val body = String(exchange.requestBody.readAllBytes())
            val path = exchange.requestURI.path
            received.add(path to body)
            val response = when {
                path == "/health" && exchange.requestMethod == "GET" ->
                    if (healthy) StubResponse(200, "text/plain", ByteArray(0))
                    else StubResponse(503, null, ByteArray(0))
                path == "/v1/chat/completions" && exchange.requestMethod == "POST" -> chatHandler(body)
                else -> StubResponse(404, null, ByteArray(0))
            }
            if (response.contentType != null) {
                exchange.responseHeaders.set("Content-Type", response.contentType)
            }
            exchange.sendResponseHeaders(
                response.status,
                if (response.body.isEmpty()) -1 else response.body.size.toLong(),
            )
            if (response.body.isNotEmpty()) {
                exchange.responseBody.write(response.body)
                exchange.responseBody.flush()
            }
            exchange.close()
        }

        fun close() {
            server.stop(0)
        }
    }

    private fun awaitActiveZero(tracker: RequestTracker) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (tracker.active() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(0, tracker.active())
    }

    private fun processAgainst(stub: StubServer, startupTimeoutMs: Long = 1500): LlamaServerProcess {
        val bin = File(tempDir, "llama-server").apply { writeText("x") }
        val model = File(tempDir, "model.gguf").apply { writeText("x") }
        return LlamaServerProcess(
            LlamaConfig(
                binPath = bin.path,
                modelPath = model.path,
                port = stub.port,
                logFile = File(tempDir, "llama.log").path,
                startupTimeoutMs = startupTimeoutMs,
                stopTimeoutMs = 200,
                launcher = FakeLauncher(),
            ),
            pollIntervalMs = 5,
        )
    }

    private fun gateway(
        cfg: GatewayConfig,
        stub: StubServer,
        process: LlamaServerProcess,
        tracker: RequestTracker,
    ): Gateway = Gateway(cfg, HttpUpstream("http://127.0.0.1:${stub.port}"), process, tracker)

    private fun get(base: String, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postJson(base: String, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postBytes(base: String, path: String, body: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    @Test
    fun healthzReportsLoadedFalseThenTrueAfterForcedStart() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val before = get(base, "/healthz")
            assertEquals(200, before.statusCode())
            assertTrue(before.body().contains("\"loaded\":false"))

            val models = get(base, "/v1/models")
            assertEquals(200, models.statusCode())
            assertTrue(models.headers().firstValue("Content-Type").orElse("").contains("application/json"))
            assertTrue(models.body().contains("\"qwen3.5-0.8b\""))

            stub.healthy = true
            assertTrue(process.start())

            val after = get(base, "/healthz")
            assertEquals(200, after.statusCode())
            assertTrue(after.body().contains("\"loaded\":true"))
            assertTrue(after.body().contains("\"active\":0"))
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun validPostForwardsTransformedBody() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        stub.healthy = true
        assertTrue(process.start())
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val request = """
                {
                  "model": "some-other-model",
                  "thinking": "strip-me",
                  "chat_template_kwargs": {"enable_thinking": true},
                  "messages": [
                    {"role": "user", "content": [{"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}]},
                    {"role": "user", "content": "hello world"}
                  ]
                }
            """.trimIndent()
            val response = postJson(base, "/v1/chat/completions", request)
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"))
            val json = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals("qwen3.5-0.8b", (json["model"] as JsonPrimitive).content)
            assertNull(json["thinking"])
            val kwargs = json["chat_template_kwargs"]?.jsonObject
            assertEquals("false", kwargs?.get("enable_thinking")?.toString())
            val messages = json["messages"]!!.jsonArray
            assertEquals("[image removed]", (messages[0].jsonObject["content"] as JsonPrimitive).content)
            assertEquals("hello world", (messages[1].jsonObject["content"] as JsonPrimitive).content)
            val chats = stub.received.filter { it.first == "/v1/chat/completions" }
            assertEquals(1, chats.size)
            assertTrue(chats.single().second.contains("\"qwen3.5-0.8b\""))
            awaitActiveZero(tracker)
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun oversizedPromptPrunesAndRelays() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        stub.healthy = true
        assertTrue(process.start())
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port, pruneBudget = 500)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val messages = (0 until 6).joinToString(",") { i ->
                """{"role":"user","content":"${"A".repeat(600)}-$i"}"""
            }
            val request = """{"messages":[$messages]}"""
            val sentCount = Json.parseToJsonElement(request).jsonObject["messages"]!!.jsonArray.size
            val response = postJson(base, "/v1/chat/completions", request)
            assertEquals(200, response.statusCode())
            val relayed = Json.parseToJsonElement(response.body()).jsonObject
            val relayedCount = relayed["messages"]!!.jsonArray.size
            assertEquals(6, sentCount)
            assertTrue(relayedCount < sentCount)
            awaitActiveZero(tracker)
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun ssePassthroughIsByteIdentical() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        stub.healthy = true
        assertTrue(process.start())
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val sse = "data: {\"role\":\"assistant\",\"content\":\"hi\"}\n\ndata: [DONE]\n\n"
            stub.chatHandler = { _ ->
                StubResponse(200, "text/event-stream", sse.toByteArray())
            }
            val response = postBytes(
                base,
                "/v1/chat/completions",
                """{"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertEquals(200, response.statusCode())
            assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse(""))
            assertArrayEquals(sse.toByteArray(), response.body())
            awaitActiveZero(tracker)
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun returns503WhenProcessStartFails() {
        val stub = StubServer()
        stub.healthy = false
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub, startupTimeoutMs = 300)
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val response = postJson(
                base,
                "/v1/chat/completions",
                """{"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("model not loaded"))
            assertTrue(response.body().contains("service_unavailable"))
            assertEquals(LlamaState.STOPPED, process.state())
            assertEquals(0, tracker.active())
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun malformedJsonBodyReturns400AndLeavesTrackerClean() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val response = postJson(base, "/v1/chat/completions", "this is not json")
            assertEquals(400, response.statusCode())
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"))
            assertTrue(response.body().contains("invalid request body"))
            assertEquals(0, tracker.active())
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun upstreamFailureReturns502AndEndsTracker() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        stub.healthy = true
        assertTrue(process.start())
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val failingUpstream = object : Upstream {
            override fun chatCompletions(body: String): UpstreamResult =
                UpstreamResult.Failure("upstream boom")
        }
        val gw = Gateway(cfg, failingUpstream, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val response = postJson(
                base,
                "/v1/chat/completions",
                """{"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertEquals(502, response.statusCode())
            val json = Json.parseToJsonElement(response.body()).jsonObject
            val error = json["error"]!!.jsonObject
            assertEquals("upstream boom", (error["message"] as JsonPrimitive).content)
            assertEquals("upstream_error", (error["type"] as JsonPrimitive).content)
            awaitActiveZero(tracker)
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun firstRequestAutoStartsProcessWhenHealthy() {
        val stub = StubServer()
        stub.healthy = true
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        val base = "http://127.0.0.1:${cfg.listenPort}"
        gw.start()
        try {
            val response = postJson(
                base,
                "/v1/chat/completions",
                """{"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertEquals(200, response.statusCode())
            assertEquals(LlamaState.RUNNING, process.state())
            val health = get(base, "/healthz")
            assertTrue(health.body().contains("\"loaded\":true"))
            awaitActiveZero(tracker)
        } finally {
            gw.stop()
            stub.close()
        }
    }

    @Test
    fun unknownRouteReturns404() {
        val stub = StubServer()
        val tracker = RequestTracker(FixedClock())
        val process = processAgainst(stub)
        val cfg = GatewayConfig(listenPort = freePort(), upstreamPort = stub.port)
        val gw = gateway(cfg, stub, process, tracker)
        gw.start()
        try {
            val response = get("http://127.0.0.1:${cfg.listenPort}", "/no-such-route")
            assertEquals(404, response.statusCode())
        } finally {
            gw.stop()
            stub.close()
        }
    }
}