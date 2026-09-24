package com.zcode.qwen35gw

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfigTest {
    private val home: String = System.getProperty("user.home")

    @Test
    fun defaultsFromEmptyEnv() {
        val app = ConfigEnv.fromEnv(emptyMap())
        assertEquals(8091, app.gateway.listenPort)
        assertEquals(8101, app.gateway.upstreamPort)
        assertEquals("127.0.0.1", app.gateway.upstreamHost)
        assertEquals("qwen3.5-0.8b", app.gateway.modelId)
        assertEquals(30_000, app.gateway.pruneBudget)
        assertEquals("$home/qwen35_08b/bin/llama-server", app.llama.binPath)
        assertEquals("$home/qwen35_08b/Qwen3.5-0.8B-Q4_K_M.gguf", app.llama.modelPath)
        assertEquals(8101, app.llama.port)
        assertEquals(32768, app.llama.ctxSize)
        assertEquals(10, app.llama.threads)
        assertEquals("qwen3.5-0.8b", app.llama.alias)
        assertEquals("$home/qwen35_08b/logs/llama-server.log", app.llama.logFile)
        assertEquals(600_000L, app.idleMs)
    }

    @Test
    fun overriddenVarsMap() {
        val env = mapOf(
            "PORT" to "9001",
            "UPSTREAM_PORT" to "9101",
            "UPSTREAM_HOST" to "10.0.0.1",
            "MODEL_ID" to "custom-model",
            "MODEL_PATH" to "/models/q.gguf",
            "LLAMA_SERVER_PATH" to "/usr/local/bin/llama-server",
            "CTX_SIZE" to "16384",
            "THREADS" to "4",
            "IDLE_MINUTES" to "25",
            "PRUNE_BUDGET" to "12000",
            "LOG_DIR" to "/var/log/qwen",
        )
        val app = ConfigEnv.fromEnv(env)
        assertEquals(9001, app.gateway.listenPort)
        assertEquals(9101, app.gateway.upstreamPort)
        assertEquals("10.0.0.1", app.gateway.upstreamHost)
        assertEquals("custom-model", app.gateway.modelId)
        assertEquals(12_000, app.gateway.pruneBudget)
        assertEquals("/usr/local/bin/llama-server", app.llama.binPath)
        assertEquals("/models/q.gguf", app.llama.modelPath)
        assertEquals(9101, app.llama.port)
        assertEquals(16_384, app.llama.ctxSize)
        assertEquals(4, app.llama.threads)
        assertEquals("custom-model", app.llama.alias)
        assertEquals("/var/log/qwen/llama-server.log", app.llama.logFile)
        assertEquals(25 * 60_000L, app.idleMs)
    }

    @Test
    fun tildeExpands() {
        val env = mapOf(
            "LLAMA_SERVER_PATH" to "~/opt/bin/llama-server",
            "MODEL_PATH" to "~/models/m.gguf",
            "LOG_DIR" to "~/logs",
        )
        val app = ConfigEnv.fromEnv(env)
        assertEquals("$home/opt/bin/llama-server", app.llama.binPath)
        assertEquals("$home/models/m.gguf", app.llama.modelPath)
        assertEquals("$home/logs/llama-server.log", app.llama.logFile)
    }

    @Test
    fun malformedPortThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigEnv.fromEnv(mapOf("PORT" to "not-a-port"))
        }
    }

    @Test
    fun zeroOrNegativeIdleAndPruneCoerceToMinimum() {
        val zeroed = ConfigEnv.fromEnv(mapOf("IDLE_MINUTES" to "0", "PRUNE_BUDGET" to "0"))
        assertEquals(60_000L, zeroed.idleMs)
        assertEquals(1, zeroed.gateway.pruneBudget)

        val negative = ConfigEnv.fromEnv(mapOf("IDLE_MINUTES" to "-5", "PRUNE_BUDGET" to "-3"))
        assertEquals(60_000L, negative.idleMs)
        assertEquals(1, negative.gateway.pruneBudget)
    }
}