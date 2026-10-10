package com.nuvio.app.features.addons

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Regression: a provider that trickles its answer — a byte now and then, so the 60 s read timeout (which
 * only measures silence) never fires — held a text request, and the IPTV page waiting on it, forever.
 * Text requests now carry a whole-call limit (as iOS's requestTimeoutMillis). A raw local socket stands in
 * for the provider: headers, then one body byte every 300 ms, forever.
 */
class TextRequestCallTimeoutTest {
    private val server = ServerSocket(0)

    @AfterTest
    fun tearDown() {
        textRequestCallTimeoutMs = 60_000L
        server.close()
    }

    @Test
    fun `a provider that trickles bytes cannot hold a text request past the call limit`() = runBlocking {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                    out.flush()
                    while (true) { out.write('['.code); out.flush(); Thread.sleep(300) }
                }
            }
        }
        textRequestCallTimeoutMs = 1_500L
        val started = System.currentTimeMillis()

        val result = runCatching { httpGetText("http://127.0.0.1:${server.localPort}/player_api.php", null) }
        val waited = System.currentTimeMillis() - started

        assertTrue(result.isFailure, "the trickled request must fail, not return")
        assertTrue(waited < 6_000, "the call limit must end it (waited ${waited}ms)")
    }
}
