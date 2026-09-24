package com.zcode.qwen35gw

import com.zcode.qwen35gw.gateway.GatewayConfig
import com.zcode.qwen35gw.runtime.LlamaConfig

data class AppConfig(
    val gateway: GatewayConfig,
    val llama: LlamaConfig,
    val idleMs: Long,
)

object ConfigEnv {
    private const val DEFAULT_PORT = 8091
    private const val DEFAULT_UPSTREAM_PORT = 8101
    private const val DEFAULT_UPSTREAM_HOST = "127.0.0.1"
    private const val DEFAULT_MODEL_ID = "qwen3.5-0.8b"
    private const val DEFAULT_MODEL_PATH = "~/qwen35_08b/Qwen3.5-0.8B-Q4_K_M.gguf"
    private const val DEFAULT_LLAMA_SERVER_PATH = "~/qwen35_08b/bin/llama-server"
    private const val DEFAULT_CTX_SIZE = 32768
    private const val DEFAULT_THREADS = 10
    private const val DEFAULT_IDLE_MINUTES = 10
    private const val DEFAULT_PRUNE_BUDGET = 30000
    private const val DEFAULT_LOG_DIR = "~/qwen35_08b/logs"
    private const val MILLIS_PER_MINUTE = 60_000L

    fun fromEnv(env: Map<String, String>): AppConfig {
        val home = System.getProperty("user.home")
        val modelId = env["MODEL_ID"] ?: DEFAULT_MODEL_ID
        val upstreamPort = env["UPSTREAM_PORT"]?.toInt() ?: DEFAULT_UPSTREAM_PORT
        val ctxSize = env["CTX_SIZE"]?.toInt() ?: DEFAULT_CTX_SIZE
        val threads = env["THREADS"]?.toInt() ?: DEFAULT_THREADS
        val modelPath = expandTilde(env["MODEL_PATH"] ?: DEFAULT_MODEL_PATH, home)
        val binPath = expandTilde(env["LLAMA_SERVER_PATH"] ?: DEFAULT_LLAMA_SERVER_PATH, home)
        val logDir = expandTilde(env["LOG_DIR"] ?: DEFAULT_LOG_DIR, home)
        val idleMinutes = (env["IDLE_MINUTES"]?.toLong() ?: DEFAULT_IDLE_MINUTES.toLong()).coerceAtLeast(1L)
        return AppConfig(
            gateway = GatewayConfig(
                listenPort = env["PORT"]?.toInt() ?: DEFAULT_PORT,
                upstreamHost = env["UPSTREAM_HOST"] ?: DEFAULT_UPSTREAM_HOST,
                upstreamPort = upstreamPort,
                modelId = modelId,
                pruneBudget = (env["PRUNE_BUDGET"]?.toInt() ?: DEFAULT_PRUNE_BUDGET).coerceAtLeast(1),
            ),
            llama = LlamaConfig(
                binPath = binPath,
                modelPath = modelPath,
                port = upstreamPort,
                ctxSize = ctxSize,
                threads = threads,
                alias = modelId,
                logFile = "$logDir/llama-server.log",
            ),
            idleMs = idleMinutes * MILLIS_PER_MINUTE,
        )
    }

    private fun expandTilde(value: String, home: String): String =
        if (value.startsWith("~/")) "$home/${value.removePrefix("~/")}" else value
}