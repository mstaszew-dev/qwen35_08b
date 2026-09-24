package com.zcode.qwen35gw.runtime

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

interface Clock {
    fun nowMillis(): Long
}

class RequestTracker(private val clock: Clock) {
    private val activeCount = AtomicInteger(0)
    private val lastRequestAt = AtomicReference<Long?>(null)

    fun begin() {
        activeCount.incrementAndGet()
        lastRequestAt.set(clock.nowMillis())
    }

    fun end() {
        activeCount.updateAndGet { it.coerceAtLeast(1) - 1 }
    }

    fun active(): Int = activeCount.get()

    fun idleMillis(now: Long): Long =
        lastRequestAt.get()?.let { now - it } ?: Long.MAX_VALUE
}

class IdleUnloadScheduler(
    private val process: LlamaServerProcess,
    private val tracker: RequestTracker,
    private val idleMs: Long,
    private val clock: Clock,
    private val executor: ScheduledExecutorService,
    private val tickMs: Long = 30_000,
) {
    fun start() {
        executor.scheduleWithFixedDelay(
            { evaluate(clock.nowMillis()) },
            tickMs,
            tickMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        executor.shutdown()
    }

    fun evaluate(now: Long) {
        if (process.state() == LlamaState.RUNNING &&
            tracker.active() == 0 &&
            tracker.idleMillis(now) >= idleMs
        ) {
            process.stop()
        }
    }

    companion object {
        fun create(
            process: LlamaServerProcess,
            tracker: RequestTracker,
            idleMs: Long,
            clock: Clock,
        ): IdleUnloadScheduler {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "idle-unload").apply { isDaemon = true }
            }
            return IdleUnloadScheduler(process, tracker, idleMs, clock, executor)
        }
    }
}