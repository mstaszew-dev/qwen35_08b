package com.zcode.qwen35gw.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LlamaServerProcessTest {

    @TempDir
    lateinit var tempDir: File

    private class FakeProcess : ManagedProcess {
        var alive = true
        var destroyCount = 0
        var dieAfterDestroyCount = 1
        val waitForCalls = mutableListOf<Long>()

        override fun isAlive(): Boolean = alive

        override fun destroy() {
            destroyCount++
            if (destroyCount >= dieAfterDestroyCount) alive = false
        }

        override fun waitFor(ms: Long): Boolean {
            waitForCalls.add(ms)
            return !alive
        }
    }

    private class FakeLauncher(
        val fakeProcess: FakeProcess = FakeProcess(),
    ) : ManagedProcessLauncher {
        val commands = mutableListOf<List<String>>()
        val logFiles = mutableListOf<String>()

        override fun launch(cmd: List<String>, logFile: String): ManagedProcess {
            commands.add(cmd)
            logFiles.add(logFile)
            return fakeProcess
        }
    }

    private fun existingFile(name: String): File =
        File(tempDir, name).apply { writeText("x") }

    private fun config(
        binPath: String,
        modelPath: String,
        launcher: ManagedProcessLauncher,
        healthCheck: (String, Int) -> Boolean = { _, _ -> true },
        startupTimeoutMs: Long = 1_000,
        stopTimeoutMs: Long = 100,
    ): LlamaConfig = LlamaConfig(
        binPath = binPath,
        modelPath = modelPath,
        logFile = File(tempDir, "llama.log").path,
        startupTimeoutMs = startupTimeoutMs,
        stopTimeoutMs = stopTimeoutMs,
        healthCheck = healthCheck,
        launcher = launcher,
    )

    @Test
    fun startLaunchesWithExactFrozenCommand() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(binPath = bin.path, modelPath = model.path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        val expected = listOf(
            bin.path, "-m", model.path,
            "--host", "127.0.0.1", "--port", "8101",
            "--ctx-size", "32768", "-ngl", "99",
            "--flash-attn", "on", "--parallel", "1",
            "--threads", "10", "--jinja",
            "--alias", "qwen3.5-0.8b",
            "--cache-type-k", "q8_0", "--cache-type-v", "q8_0",
            "--reasoning", "off",
        )
        assertEquals(listOf(expected), launcher.commands)
        assertEquals(File(tempDir, "llama.log").path, launcher.logFiles.single())
    }

    @Test
    fun startPollsUntilHealthyThenRunning() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        var calls = 0
        val proc = LlamaServerProcess(
            config(
                binPath = bin.path,
                modelPath = model.path,
                launcher = launcher,
                healthCheck = { _, _ -> calls++; calls >= 3 },
            ),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        assertEquals(LlamaState.RUNNING, proc.state())
        assertTrue(calls >= 3)
    }

    @Test
    fun startTimesOutThenFalseAndStopped() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(
                binPath = bin.path,
                modelPath = model.path,
                launcher = launcher,
                healthCheck = { _, _ -> false },
                startupTimeoutMs = 50,
            ),
            pollIntervalMs = 5,
        )
        assertFalse(proc.start())
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(1, launcher.commands.size)
        assertTrue(launcher.fakeProcess.destroyCount >= 1)
    }

    @Test
    fun startIdempotentWhileRunning() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(binPath = bin.path, modelPath = model.path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        assertTrue(proc.start())
        assertEquals(1, launcher.commands.size)
        assertEquals(LlamaState.RUNNING, proc.state())
    }

    @Test
    fun missingBinaryNeverLaunches() {
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(binPath = File(tempDir, "no-such-bin").path, modelPath = model.path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertFalse(proc.start())
        assertEquals(LlamaState.STOPPED, proc.state())
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun missingModelNeverLaunches() {
        val bin = existingFile("llama-server")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(binPath = bin.path, modelPath = File(tempDir, "no-such-model.gguf").path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertFalse(proc.start())
        assertEquals(LlamaState.STOPPED, proc.state())
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun stopDestroysAndWaits() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            config(binPath = bin.path, modelPath = model.path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        proc.stop()
        assertEquals(1, launcher.fakeProcess.destroyCount)
        assertEquals(listOf(100L), launcher.fakeProcess.waitForCalls)
        assertFalse(launcher.fakeProcess.alive)
        assertEquals(LlamaState.STOPPED, proc.state())
    }

    @Test
    fun stopEscalatesWhenNotDeadWithinTimeout() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        launcher.fakeProcess.dieAfterDestroyCount = 2
        val proc = LlamaServerProcess(
            config(binPath = bin.path, modelPath = model.path, launcher = launcher),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        proc.stop()
        assertEquals(2, launcher.fakeProcess.destroyCount)
        assertEquals(listOf(100L, 100L), launcher.fakeProcess.waitForCalls)
        assertFalse(launcher.fakeProcess.alive)
        assertEquals(LlamaState.STOPPED, proc.state())
    }

    @Test
    fun stateTransitionsStoppedStartingRunningStopped() {
        val bin = existingFile("llama-server")
        val model = existingFile("model.gguf")
        val launcher = FakeLauncher()
        var observed: LlamaState? = null
        var self: LlamaServerProcess? = null
        val cfg = config(
            binPath = bin.path,
            modelPath = model.path,
            launcher = launcher,
            healthCheck = { _, _ ->
                if (observed == null) observed = self!!.state()
                true
            },
        )
        val proc = LlamaServerProcess(cfg, pollIntervalMs = 1)
        self = proc
        assertEquals(LlamaState.STOPPED, proc.state())
        assertTrue(proc.start())
        assertEquals(LlamaState.STARTING, observed)
        assertEquals(LlamaState.RUNNING, proc.state())
        proc.stop()
        assertEquals(LlamaState.STOPPED, proc.state())
    }
}