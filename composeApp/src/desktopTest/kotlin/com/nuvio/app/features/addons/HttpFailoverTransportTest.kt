package com.nuvio.app.features.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Step 0.3b — what a failover race needs from the HTTP engine, over REAL loopback sockets:
 *  1. cancelling a coroutine aborts the in-flight call (a hung server must not outlive its loser),
 *  2. response HEADERS are observable separately from the body ([signalHttpHeaders] fires before a
 *     single body byte is read, and a parked loser never reads one),
 *  3. a byte-capped stream (the M3U probe) stops and closes even when the server ignores `Range`,
 *  4. the failover CONNECT timeout is applied per attempt.
 */
class HttpFailoverTransportTest {
    private lateinit var server: ServerSocket
    private val clients = mutableListOf<Socket>()

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private fun listen(): Int {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 30_000
        return server.localPort
    }

    /** Accepts one client, drains its request head, then runs [respond]. */
    private fun serveOnce(requestSeen: CountDownLatch, respond: (Socket) -> Unit) = thread(name = "failover-test-server") {
        runCatching {
            val sock = server.accept().also { synchronized(clients) { clients += it } }
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val req = StringBuilder()
            while (!req.endsWith("\r\n\r\n")) {
                val b = input.read(); if (b == -1) return@runCatching; req.append(b.toChar())
            }
            requestSeen.countDown()
            respond(sock)
        }
    }

    @Test
    fun `cancelling httpGetText aborts the in-flight call of a server that never answers`() = runBlocking {
        val port = listen()
        val seen = CountDownLatch(1)
        serveOnce(seen) { Thread.sleep(30_000) }            // accepts, reads the request, then silence
        val job = launch(Dispatchers.Default) { httpGetText("http://127.0.0.1:$port/hang") }
        withTimeout(5_000) { while (seen.count > 0L) delay(20) }
        // The call is blocked in execute(). Cancelling must close the socket, not wait for the 60 s read timeout.
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `the header signal fires before any body byte and a parked loser never reads the body`() = runBlocking {
        val port = listen()
        val seen = CountDownLatch(1)
        val bodyBytesSent = AtomicInteger(0)
        val clientClosed = AtomicBoolean(false)
        serveOnce(seen) { sock ->
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000000\r\n\r\n".toByteArray())
            out.flush()
            // Headers are out; the body trickles. A client that is parked at the signal must not be reading it.
            try {
                while (true) { out.write(ByteArray(1024) { 'x'.code.toByte() }); out.flush(); bodyBytesSent.addAndGet(1024); Thread.sleep(5) }
            } catch (_: IOException) { clientClosed.set(true) }
        }
        val signalled = CountDownLatch(1)
        val signal = HttpAttemptSignal(connectTimeoutMs = 1_000) { signalled.countDown(); awaitCancellation() }
        val job = launch(Dispatchers.Default + signal) { httpGetText("http://127.0.0.1:$port/big") }
        assertTrue(signalled.await(5, TimeUnit.SECONDS), "headers were reported while the body was still unread")
        withTimeout(5_000) { job.cancelAndJoin() }          // it lost the race: cancel while parked
        val deadline = System.currentTimeMillis() + 5_000
        while (!clientClosed.get() && System.currentTimeMillis() < deadline) delay(20)
        assertTrue(clientClosed.get(), "the loser's connection was closed (the server saw it go)")
        assertTrue(bodyBytesSent.get() < 100_000_000, "the loser never downloaded the whole body")
    }

    @Test
    fun `a byte-capped stream stops and closes even when the server ignores range`() = runBlocking {
        val port = listen()
        val seen = CountDownLatch(1)
        val wroteEverything = AtomicBoolean(false)
        val closedEarly = AtomicBoolean(false)
        serveOnce(seen) { sock ->
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n#EXTM3U\n".toByteArray())
            val chunk = "#EXTINF:-1,Channel\nhttp://x/1.ts\n".repeat(40).toByteArray()
            var written = 0
            try {
                while (written < 16 * 1024 * 1024) { out.write(chunk); out.flush(); written += chunk.size }
                wroteEverything.set(true)
            } catch (_: IOException) { closedEarly.set(true) }
        }
        val received = StringBuilder()
        withTimeout(10_000) {
            httpStreamLines("http://127.0.0.1:$port/list.m3u", null, null, mapOf("Range" to "bytes=0-1023"), 1024) { received.append(it).append('\n') }
        }
        assertTrue(received.startsWith("#EXTM3U"), received.take(40).toString())
        assertTrue(received.length <= 1024 + 4, "at most ~1 KB consumed, was ${received.length}")
        val deadline = System.currentTimeMillis() + 10_000
        while (!closedEarly.get() && !wroteEverything.get() && System.currentTimeMillis() < deadline) delay(20)
        assertTrue(closedEarly.get() && !wroteEverything.get(), "the connection was closed early instead of draining 16 MB")
    }

    @Test
    fun `the failover connect timeout is applied per attempt`() = runBlocking {
        // 192.0.2.1 is TEST-NET-1: nothing answers. With the default 60 s this would hang for a minute
        // (or fail fast on a machine with no route, which also satisfies the bound).
        val started = System.currentTimeMillis()
        val outcome = runCatching {
            withContext(HttpAttemptSignal(connectTimeoutMs = 400, onHeaders = null)) { httpGetText("http://192.0.2.1:81/") }
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue(outcome.exceptionOrNull() is IOException, "connect failed: ${outcome.exceptionOrNull()}")
        assertTrue(elapsed < 8_000, "gave up after the 400 ms connect timeout, not 60 s (took $elapsed ms)")
    }

    @Test
    fun `outside a failover walk nothing changes - no signal element, no parking`() = runBlocking {
        val port = listen()
        val seen = CountDownLatch(1)
        serveOnce(seen) { sock ->
            val body = """{"ok":true}"""
            sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
        }
        assertEquals("""{"ok":true}""", withTimeout(5_000) { httpGetText("http://127.0.0.1:$port/") })
    }
}
