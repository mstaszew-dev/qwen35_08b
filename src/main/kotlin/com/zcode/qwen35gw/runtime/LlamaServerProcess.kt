package com.zcode.qwen35gw.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

data class LlamaConfig(
    val binPath: String,
    val modelPath: String,
    val host: String = "127.0.0.1",
    val port: Int = 8101,
    val ctxSize: Int = 32768,
    val threads: Int = 10,
    val alias: String = "qwen3.5-0.8b",
    val logFile: String,
    val startupTimeoutMs: Long = 30_000,
    val stopTimeoutMs: Long = 5_000,
    val healthCheck: (host: String, port: Int) -> Boolean = { h, p -> HttpHealthCheck.isHealthy(h, p) },
    val launcher: ManagedProcessLauncher = ProcessLauncherImpl,
)

interface ManagedProcess {
    fun isAlive(): Boolean
    fun destroy()
    fun waitFor(ms: Long): Boolean
}

interface ManagedProcessLauncher {
    fun launch(cmd: List<String>, logFile: String): ManagedProcess
}

enum class LlamaState { STOPPED, STARTING, RUNNING }

object HttpHealthCheck {
    fun isHealthy(host: String, port: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI("http://$host:$port/health").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}

object ProcessLauncherImpl : ManagedProcessLauncher {
    override fun launch(cmd: List<String>, logFile: String): ManagedProcess {
        val builder = ProcessBuilder(cmd)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(File(logFile)))
        return JavaManagedProcess(builder.start())
    }
}

private class JavaManagedProcess(private val process: Process) : ManagedProcess {
    private var gracefulSignaled = false

    override fun isAlive(): Boolean = process.isAlive

    override fun destroy() {
        if (gracefulSignaled) {
            process.destroyForcibly()
        } else {
            process.destroy()
            gracefulSignaled = true
        }
    }

    override fun waitFor(ms: Long): Boolean = process.waitFor(ms, TimeUnit.MILLISECONDS)
}

class LlamaServerProcess(
    private val cfg: LlamaConfig,
    private val pollIntervalMs: Long = 200,
) {
    private val lock = Any()

    @Volatile
    private var currentState: LlamaState = LlamaState.STOPPED

    private var currentProcess: ManagedProcess? = null

    fun state(): LlamaState = currentState

    fun start(): Boolean {
        synchronized(lock) {
            if (currentState == LlamaState.RUNNING) return true
            if (!File(cfg.binPath).exists() || !File(cfg.modelPath).exists()) {
                currentState = LlamaState.STOPPED
                return false
            }
            currentState = LlamaState.STARTING
            val process = cfg.launcher.launch(command(), cfg.logFile)
            currentProcess = process
            val deadline = System.nanoTime() + cfg.startupTimeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (cfg.healthCheck(cfg.host, cfg.port)) {
                    currentState = LlamaState.RUNNING
                    return true
                }
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                Thread.sleep(minOf(pollIntervalMs, remainingMs))
            }
            process.destroy()
            currentProcess = null
            currentState = LlamaState.STOPPED
            return false
        }
    }

    fun stop() {
        synchronized(lock) {
            val process = currentProcess
            if (process != null) {
                process.destroy()
                if (!process.waitFor(cfg.stopTimeoutMs) && process.isAlive()) {
                    process.destroy()
                    process.waitFor(cfg.stopTimeoutMs)
                }
                currentProcess = null
            }
            currentState = LlamaState.STOPPED
        }
    }

    private fun command(): List<String> = listOf(
        cfg.binPath, "-m", cfg.modelPath,
        "--host", cfg.host, "--port", cfg.port.toString(),
        "--ctx-size", cfg.ctxSize.toString(),
        "-ngl", "99",
        "--flash-attn", "on",
        "--parallel", "1",
        "--threads", cfg.threads.toString(),
        "--jinja",
        "--alias", cfg.alias,
        "--cache-type-k", "q8_0",
        "--cache-type-v", "q8_0",
        "--reasoning", "off",
    )
}