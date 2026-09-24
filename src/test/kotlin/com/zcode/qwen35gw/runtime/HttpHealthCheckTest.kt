package com.zcode.qwen35gw.runtime

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

class HttpHealthCheckTest {

    private fun respondingServer(status: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    private fun stallingServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") {
            Thread.sleep(10_000)
        }
        server.start()
        return server
    }

    private fun port(server: HttpServer): Int = server.address.port

    @Test
    fun healthyOn200() {
        val server = respondingServer(200)
        try {
            assertTrue(HttpHealthCheck.isHealthy("127.0.0.1", port(server)))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unhealthyOn500() {
        val server = respondingServer(500)
        try {
            assertFalse(HttpHealthCheck.isHealthy("127.0.0.1", port(server)))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unhealthyOnConnectionRefused() {
        val server = respondingServer(200)
        val refusedPort = port(server)
        server.stop(0)
        assertFalse(HttpHealthCheck.isHealthy("127.0.0.1", refusedPort))
    }

    @Test
    fun returnsFalseWithinShortTimeoutWhenServerStalls() {
        val server = stallingServer()
        try {
            val start = Instant.now()
            val healthy = HttpHealthCheck.isHealthy("127.0.0.1", port(server))
            val elapsed = Duration.between(start, Instant.now()).toMillis()
            assertFalse(healthy)
            assertTrue(
                elapsed < 5_000,
                "expected bounded timeout but took ${elapsed}ms",
            )
        } finally {
            server.stop(0)
        }
    }
}