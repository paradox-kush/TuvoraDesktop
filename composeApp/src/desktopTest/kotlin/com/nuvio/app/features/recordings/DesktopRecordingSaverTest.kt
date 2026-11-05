package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.RecordingProblem
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import com.nuvio.app.features.recordings.internal.DesktopRecordingSaver
import com.nuvio.app.features.recordings.internal.ResumeMeta
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A scripted loopback server (the JDK's own; the desktop test classpath has no MockWebServer). One reply is consumed per request, in order. */
private class ScriptedServer {
    sealed interface Reply {
        class Body(val code: Int = 200, val headers: Map<String, String> = emptyMap(), val bytes: ByteArray = ByteArray(0)) : Reply

        /** Declares [declared] bytes, sends [bytes], then drops the connection. */
        class Truncated(val declared: Long, val bytes: ByteArray) : Reply

        /** Closes the connection without answering. */
        data object Drop : Reply

        /** Declares [declared] bytes, sends [bytes], then goes quiet for [quietMs]. */
        class Stall(val declared: Long, val bytes: ByteArray, val quietMs: Long) : Reply
    }

    class Seen(val path: String, val headers: Map<String, List<String>>)

    private val replies = LinkedBlockingQueue<Reply>()
    val seen = CopyOnWriteArrayList<Seen>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    fun enqueue(r: Reply) = replies.add(r)
    fun stop() = server.stop(0)
    val requestCount get() = seen.size

    private fun handle(ex: HttpExchange) {
        seen += Seen(ex.requestURI.path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value })
        val reply = replies.poll()
        try {
            when (reply) {
                null, Reply.Drop -> ex.close()
                is Reply.Body -> {
                    reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                    if (reply.bytes.isEmpty()) {
                        ex.sendResponseHeaders(reply.code, -1)
                    } else {
                        ex.sendResponseHeaders(reply.code, reply.bytes.size.toLong())
                        ex.responseBody.use { it.write(reply.bytes) }
                    }
                }
                is Reply.Truncated -> {
                    ex.sendResponseHeaders(200, reply.declared)
                    ex.responseBody.write(reply.bytes)
                    ex.responseBody.flush()
                    ex.close()
                }
                is Reply.Stall -> {
                    ex.sendResponseHeaders(200, reply.declared)
                    ex.responseBody.write(reply.bytes)
                    ex.responseBody.flush()
                    Thread.sleep(reply.quietMs)
                    ex.close()
                }
            }
        } catch (_: Exception) {
            // The client went away (stall, cancel) or the declared length was not met: expected in these scripts.
        } finally {
            runCatching { ex.close() }
        }
    }
}

class DesktopRecordingSaverTest {
    private lateinit var server: ScriptedServer
    private lateinit var dir: File
    private var online = true

    @BeforeTest
    fun setUp() {
        server = ScriptedServer()
        dir = Files.createTempDirectory("rec-saver").toFile()
        online = true
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun req(vararg paths: String, resume: Long = 0, headers: Map<String, String> = emptyMap(), check: (suspend (Long) -> RecordingProblem?)? = null) =
        SaveRequest(
            candidates = paths.map { server.url(it) }, headers = headers,
            targetPath = File(dir, "show.ts").path, resumeFromBytes = resume, estimatedBytes = 0, spaceCheck = check,
        )

    private fun saver(stallMs: Long = 30_000, tickMs: Long = 5_000) =
        DesktopRecordingSaver(isOnline = { online }, stallMs = stallMs, watchdogTickMs = tickMs)

    private fun body(s: String) = ScriptedServer.Reply.Body(bytes = s.toByteArray())
    private fun status(code: Int) = ScriptedServer.Reply.Body(code = code)

    @Test
    fun `a plain download is renamed to the target`() = runBlocking {
        server.enqueue(body("0123456789"))
        val written = mutableListOf<Long>()
        val got = saver().save(req("/a.ts")) { written += it }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("0123456789", File(dir, "show.ts").readText())
        assertTrue(!File(dir, "show.ts.part").exists())
        assertEquals(10L, written.last())
    }

    @Test
    fun `the channel headers are sent and none are invented`() = runBlocking {
        server.enqueue(body("x"))
        saver().save(req("/a.ts", headers = mapOf("User-Agent" to "ProviderUA/1.0"))) { }
        assertEquals(listOf("ProviderUA/1.0"), server.seen.single().headers["user-agent"])
    }

    @Test
    fun `a partial file resumes with a range request when the server answers 206`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/a.ts"), null, null)
        server.enqueue(ScriptedServer.Reply.Body(206, mapOf("Content-Range" to "bytes 5-9/10"), "56789".toByteArray()))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals(listOf("bytes=5-"), server.seen.single().headers["range"])
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a server that ignores the range restarts from zero`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/a.ts"), null, null)
        server.enqueue(body("0123456789"))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a partial from another link is never resumed`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/other.ts"), null, null)
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertNull(server.seen.single().headers["range"])
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a resume sends If-Range with the strong etag`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/a.ts"), "\"v1\"", null)
        server.enqueue(ScriptedServer.Reply.Body(206, mapOf("Content-Range" to "bytes 5-9/10"), "56789".toByteArray()))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertEquals(listOf("\"v1\""), server.seen.single().headers["if-range"])
    }

    @Test
    fun `a 206 that starts elsewhere restarts from zero instead of appending`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/a.ts"), null, null)
        server.enqueue(ScriptedServer.Reply.Body(206, mapOf("Content-Range" to "bytes 3-9/10"), "3456789".toByteArray()))
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertNull(server.seen[1].headers["range"])
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a 416 on resume throws the partial away and starts again`() = runBlocking {
        File(dir, "show.ts.part").writeText("0123456789ABC")
        ResumeMeta.write(File(dir, "show.ts.part"), server.url("/a.ts"), null, null)
        server.enqueue(status(416))
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 13)) { })
        assertNull(server.seen[1].headers["range"])
    }

    @Test
    fun `candidates are tried in order and a 4xx moves on`() = runBlocking {
        server.enqueue(status(404))
        server.enqueue(body("ok"))
        val got = saver().save(req("/a.ts", "/b.ts")) { }
        assertEquals(SaveOutcome.Done(2), got)
        assertEquals(listOf("/a.ts", "/b.ts"), server.seen.map { it.path })
    }

    @Test
    fun `every candidate refused is PROVIDER_REFUSED and not offline`() = runBlocking {
        server.enqueue(status(403))
        server.enqueue(status(403))
        val got = saver().save(req("/a.ts", "/b.ts")) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.PROVIDER_REFUSED, got.problem)
        assertEquals(false, got.offline)
    }

    @Test
    fun `a 5xx is the provider's trouble and is retried as a network fault`() = runBlocking {
        server.enqueue(status(503))
        val got = saver().save(req("/a.ts")) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.NETWORK, got.problem)
        assertEquals(false, got.offline)
    }

    @Test
    fun `no network is reported offline and stops trying other links`() = runBlocking {
        online = false
        server.enqueue(ScriptedServer.Reply.Drop)
        server.enqueue(ScriptedServer.Reply.Drop) // the JDK client retries a dropped GET once
        server.enqueue(body("never"))
        val got = saver().save(req("/a.ts", "/b.ts")) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.NETWORK, got.problem)
        assertEquals(true, got.offline)
        assertTrue(server.seen.none { it.path == "/b.ts" }, "offline stops before the second link")
    }

    @Test
    fun `a body shorter than its length is a failed save that keeps the partial`() = runBlocking {
        server.enqueue(ScriptedServer.Reply.Truncated(10, "0123".toByteArray()))
        val got = saver().save(req("/a.ts")) { } as SaveOutcome.Failed
        assertEquals(false, got.offline)
        assertTrue(File(dir, "show.ts.part").exists())
        assertTrue(!File(dir, "show.ts").exists())
    }

    @Test
    fun `the space check is asked every 16 MB and can stop the save`() = runBlocking {
        server.enqueue(ScriptedServer.Reply.Body(bytes = ByteArray(17 * 1024 * 1024)))
        var asked = 0
        val got = saver().save(req("/a.ts", check = { asked++; RecordingProblem.NO_SPACE })) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.NO_SPACE, got.problem)
        assertEquals(1, asked)
    }

    @Test
    fun `a body that goes quiet is STALLED and keeps what arrived`() = runBlocking {
        server.enqueue(ScriptedServer.Reply.Stall(1000, "01234".toByteArray(), quietMs = 10_000))
        val got = withTimeout(8_000) { saver(stallMs = 400, tickMs = 50).save(req("/a.ts")) { } } as SaveOutcome.Failed
        assertEquals(RecordingProblem.STALLED, got.problem)
        assertEquals(false, got.offline)
        assertEquals(5L, got.writtenBytes)
        assertEquals("01234", File(dir, "show.ts.part").readText())
    }

    @Test
    fun `cancelling a save stops it promptly and rethrows the cancellation`() = runBlocking {
        server.enqueue(ScriptedServer.Reply.Stall(1000, "01234".toByteArray(), quietMs = 10_000))
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { saver().save(req("/a.ts")) { } }
        delay(500) // let it connect and read the first bytes
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        val error = runCatching { job.await() }.exceptionOrNull()
        assertTrue(error is CancellationException, "cancellation must not be swallowed into a failure outcome")
    }
}
