package com.zcode.qwen35gw

import com.zcode.qwen35gw.gateway.Gateway
import com.zcode.qwen35gw.gateway.HttpUpstream
import com.zcode.qwen35gw.runtime.Clock
import com.zcode.qwen35gw.runtime.IdleUnloadScheduler
import com.zcode.qwen35gw.runtime.LlamaServerProcess
import com.zcode.qwen35gw.runtime.RequestTracker
import java.util.concurrent.CountDownLatch

fun main() {
    val app = ConfigEnv.fromEnv(System.getenv())
    val process = LlamaServerProcess(app.llama)
    val clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }
    val tracker = RequestTracker(clock)
    val scheduler = IdleUnloadScheduler.create(process, tracker, app.idleMs, clock).apply { start() }
    val gateway = Gateway(
        app.gateway,
        HttpUpstream("http://${app.gateway.upstreamHost}:${app.gateway.upstreamPort}"),
        process,
        tracker,
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        gateway.stop()
        scheduler.stop()
        process.stop()
    })
    gateway.start()
    println("qwen35-gw listening on 127.0.0.1:${app.gateway.listenPort} (model ${app.gateway.modelId})")
    CountDownLatch(1).await()
}