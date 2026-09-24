package com.zcode.qwen35gw.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class IdleUnloadTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var bin: File
    private lateinit var model: File
    private lateinit var clock: FakeClock

    private class FakeClock(@Volatile var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    private class FakeProcess : ManagedProcess {
        var alive = true
        var destroyCount = 0

        override fun isAlive(): Boolean = alive

        override fun destroy() {
            destroyCount++
            alive = false
        }

        override fun waitFor(ms: Long): Boolean = !alive
    }

    private class FakeLauncher(
        val fakeProcess: FakeProcess = FakeProcess(),
    ) : ManagedProcessLauncher {
        val commands = mutableListOf<List<String>>()

        override fun launch(cmd: List<String>, logFile: String): ManagedProcess {
            commands.add(cmd)
            return fakeProcess
        }
    }

    private class NoopScheduledFuture : ScheduledFuture<Any?> {
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: Delayed): Int = 0
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
    }

    private class FakeScheduledExecutor : ScheduledExecutorService {
        var capturedCommand: Runnable? = null
        var initialDelayMs: Long = -1L
        var delayMs: Long = -1L
        var capturedUnit: TimeUnit? = null
        var shutdownCalled = false

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            capturedCommand = command
            initialDelayMs = initialDelay
            delayMs = delay
            capturedUnit = unit
            return NoopScheduledFuture()
        }

        override fun shutdown() {
            shutdownCalled = true
        }

        override fun isShutdown(): Boolean = shutdownCalled

        override fun shutdownNow(): MutableList<Runnable> {
            shutdownCalled = true
            return mutableListOf()
        }

        override fun isTerminated(): Boolean = shutdownCalled

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

        override fun execute(command: Runnable) = throw UnsupportedOperationException("unused")
        override fun <T> submit(task: Callable<T>): Future<T> = throw UnsupportedOperationException("unused")
        override fun <T> submit(task: Runnable, result: T): Future<T> = throw UnsupportedOperationException("unused")
        override fun submit(task: Runnable): Future<*> = throw UnsupportedOperationException("unused")

        override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
            throw UnsupportedOperationException("unused")

        override fun <T> invokeAll(
            tasks: MutableCollection<out Callable<T>>,
            timeout: Long,
            unit: TimeUnit,
        ): MutableList<Future<T>> = throw UnsupportedOperationException("unused")

        override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T =
            throw UnsupportedOperationException("unused")

        override fun <T> invokeAny(
            tasks: MutableCollection<out Callable<T>>,
            timeout: Long,
            unit: TimeUnit,
        ): T = throw UnsupportedOperationException("unused")

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException("unused")

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException("unused")

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = throw UnsupportedOperationException("unused")
    }

    @BeforeEach
    fun setUp() {
        bin = File(tempDir, "llama-server").apply { writeText("x") }
        model = File(tempDir, "model.gguf").apply { writeText("x") }
        clock = FakeClock(0L)
    }

    private fun startRunningProcess(launcher: FakeLauncher = FakeLauncher()): LlamaServerProcess {
        val proc = LlamaServerProcess(
            LlamaConfig(
                binPath = bin.path,
                modelPath = model.path,
                logFile = File(tempDir, "llama.log").path,
                startupTimeoutMs = 1_000,
                stopTimeoutMs = 100,
                healthCheck = { _, _ -> true },
                launcher = launcher,
            ),
            pollIntervalMs = 1,
        )
        assertTrue(proc.start())
        assertEquals(LlamaState.RUNNING, proc.state())
        return proc
    }

    private fun trackerStamping(atMillis: Long): RequestTracker {
        clock.now = atMillis
        val tracker = RequestTracker(clock)
        tracker.begin()
        tracker.end()
        return tracker
    }

    private fun schedulerFor(
        proc: LlamaServerProcess,
        tracker: RequestTracker,
        idleMs: Long = 60_000L,
        executor: ScheduledExecutorService = FakeScheduledExecutor(),
        tickMs: Long = 30_000L,
    ): IdleUnloadScheduler = IdleUnloadScheduler(proc, tracker, idleMs, clock, executor, tickMs)

    @Test
    fun noUnloadBeforeIdleMs() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val scheduler = schedulerFor(proc, tracker)
        scheduler.evaluate(59_999L)
        assertEquals(LlamaState.RUNNING, proc.state())
        assertEquals(0, launcher.fakeProcess.destroyCount)
    }

    @Test
    fun unloadExactlyAtIdleMsWhenActiveZero() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val scheduler = schedulerFor(proc, tracker)
        scheduler.evaluate(60_000L)
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(1, launcher.fakeProcess.destroyCount)
    }

    @Test
    fun unloadAfterIdleMsWhenActiveZero() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val scheduler = schedulerFor(proc, tracker)
        scheduler.evaluate(60_001L)
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(1, launcher.fakeProcess.destroyCount)
    }

    @Test
    fun noUnloadWhileActiveRequestEvenPastIdleMs() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val scheduler = schedulerFor(proc, tracker)
        tracker.begin()
        scheduler.evaluate(600_000L)
        assertEquals(LlamaState.RUNNING, proc.state())
        assertEquals(0, launcher.fakeProcess.destroyCount)
        assertEquals(1, tracker.active())
        tracker.end()
        scheduler.evaluate(600_000L)
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(1, launcher.fakeProcess.destroyCount)
    }

    @Test
    fun noUnloadWhenAlreadyStopped() {
        val launcher = FakeLauncher()
        val proc = LlamaServerProcess(
            LlamaConfig(
                binPath = bin.path,
                modelPath = model.path,
                logFile = File(tempDir, "llama.log").path,
                startupTimeoutMs = 1_000,
                stopTimeoutMs = 100,
                healthCheck = { _, _ -> true },
                launcher = launcher,
            ),
            pollIntervalMs = 1,
        )
        val tracker = trackerStamping(0L)
        val scheduler = schedulerFor(proc, tracker)
        scheduler.evaluate(600_000L)
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(0, launcher.fakeProcess.destroyCount)
        assertTrue(launcher.commands.isEmpty())
    }

    @Test
    fun evaluateIsDeterministicGivenInjectedNowOnly() {
        clock.now = 0L
        val launcherA = FakeLauncher()
        val procA = startRunningProcess(launcherA)
        val trackerA = trackerStamping(0L)
        val schedulerA = schedulerFor(procA, trackerA)
        schedulerA.evaluate(0L)
        assertEquals(LlamaState.RUNNING, procA.state())
        schedulerA.evaluate(0L)
        assertEquals(LlamaState.RUNNING, procA.state())
        assertEquals(0, launcherA.fakeProcess.destroyCount)
        assertEquals(0L, clock.nowMillis())
        schedulerA.evaluate(60_000L)
        assertEquals(LlamaState.STOPPED, procA.state())

        val launcherB = FakeLauncher()
        val procB = startRunningProcess(launcherB)
        val trackerB = trackerStamping(0L)
        val schedulerB = schedulerFor(procB, trackerB)
        schedulerB.evaluate(0L)
        assertEquals(LlamaState.RUNNING, procB.state())
        schedulerB.evaluate(60_000L)
        assertEquals(LlamaState.STOPPED, procB.state())
        assertEquals(0L, clock.nowMillis())
    }

    @Test
    fun requestTrackerBeginEndActiveIdleSemantics() {
        clock.now = 1_000L
        val tracker = RequestTracker(clock)
        assertEquals(0, tracker.active())
        assertEquals(Long.MAX_VALUE, tracker.idleMillis(1_000L))
        tracker.begin()
        assertEquals(1, tracker.active())
        assertEquals(1_500L, tracker.idleMillis(2_500L))
        clock.now = 2_000L
        tracker.begin()
        assertEquals(2, tracker.active())
        assertEquals(500L, tracker.idleMillis(2_500L))
        tracker.end()
        assertEquals(1, tracker.active())
        tracker.end()
        assertEquals(0, tracker.active())
        tracker.end()
        assertEquals(0, tracker.active())
    }

    @Test
    fun neverBeganIdleIsInfinite() {
        clock.now = 42L
        val tracker = RequestTracker(clock)
        assertEquals(Long.MAX_VALUE, tracker.idleMillis(clock.nowMillis()))
        tracker.begin()
        assertEquals(0L, tracker.idleMillis(clock.nowMillis()))
    }

    @Test
    fun runningNeverBeganProcessUnloadsOnFirstTick() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = RequestTracker(clock)
        val scheduler = schedulerFor(proc, tracker, 60_000L)
        assertEquals(0, tracker.active())
        scheduler.evaluate(clock.nowMillis())
        assertEquals(LlamaState.STOPPED, proc.state())
        assertEquals(1, launcher.fakeProcess.destroyCount)
    }

    @Test
    fun startSchedulesFixedDelayTickAndStopShutsDownExecutor() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val executor = FakeScheduledExecutor()
        val scheduler = schedulerFor(proc, tracker, 60_000L, executor, 30_000L)
        scheduler.start()
        assertEquals(30_000L, executor.initialDelayMs)
        assertEquals(30_000L, executor.delayMs)
        assertEquals(TimeUnit.MILLISECONDS, executor.capturedUnit)
        val command = executor.capturedCommand
        assertTrue(command != null)
        clock.now = 0L
        command!!.run()
        assertEquals(LlamaState.RUNNING, proc.state())
        clock.now = 60_000L
        command.run()
        assertEquals(LlamaState.STOPPED, proc.state())
        scheduler.stop()
        assertTrue(executor.shutdownCalled)
    }

    @Test
    fun createBuildsSchedulerThatEvaluatesWithRealExecutor() {
        val launcher = FakeLauncher()
        val proc = startRunningProcess(launcher)
        val tracker = trackerStamping(0L)
        val scheduler = IdleUnloadScheduler.create(proc, tracker, 60_000L, clock)
        try {
            scheduler.evaluate(0L)
            assertEquals(LlamaState.RUNNING, proc.state())
            scheduler.evaluate(60_000L)
            assertEquals(LlamaState.STOPPED, proc.state())
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun trackerStaysConsistentUnderConcurrentBeginEnd() {
        val tracker = RequestTracker(clock)
        val threads = (1..8).map {
            Thread {
                repeat(1_000) {
                    tracker.begin()
                    tracker.end()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, tracker.active())
        assertFalse(tracker.active() < 0)
    }
}
