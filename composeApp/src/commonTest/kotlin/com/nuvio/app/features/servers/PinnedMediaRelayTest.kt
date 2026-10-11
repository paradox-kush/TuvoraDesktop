package com.nuvio.app.features.servers

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The relay on real loopback sockets, with a fake pinned upstream. */
class PinnedMediaRelayTest {
    private val authority = "nas.local:8920"

    private class Upstream(val handler: (HttpRequestData) -> Triple<HttpStatusCode, Map<String, List<String>>, ByteArray>) : ServerHttp {
        val requests = mutableListOf<HttpRequestData>()
        private val client = HttpClient(
            MockEngine { request ->
                requests += request
                val (status, headers, body) = handler(request)
                respond(body, status, io.ktor.http.Headers.build { headers.forEach { (k, v) -> v.forEach { append(k, it) } } })
            },
        ) { install(HttpTimeout) }

        override fun clientFor(url: String) = client
    }

    private data class Raw(val head: String, val body: ByteArray)

    private suspend fun exchange(port: Int, request: String): Raw = withContext(Dispatchers.Default) {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            val write = socket.openWriteChannel(autoFlush = true)
            write.writeStringUtf8(request)
            write.flush()
            val bytes = withTimeout(20_000) { socket.openReadChannel().readRemaining().readByteArray() }
            socket.close()
            val text = bytes.decodeToString()
            val split = text.indexOf("\r\n\r\n")
            val headEnd = if (split < 0) bytes.size else split
            Raw(text.substring(0, headEnd), bytes.copyOfRange(minOf(headEnd + 4, bytes.size), bytes.size))
        } finally {
            selector.close()
        }
    }

    private fun get(session: RelaySession, path: String, extra: String = "") =
        "GET /${session.token}$path HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n$extra\r\n"

    @Test
    fun `range requests are forwarded to the pinned authority and streamed back`() = runTest {
        val upstream = Upstream { _ ->
            Triple(HttpStatusCode.PartialContent, mapOf("Content-Range" to listOf("bytes 0-9/100"), "Content-Type" to listOf("video/mp4"), "Set-Cookie" to listOf("x=1")), ByteArray(10) { it.toByte() })
        }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val response = exchange(session.port, get(session, "/Videos/1/stream?static=true&api_key=k", "Range: bytes=0-9\r\nCookie: a=b\r\n"))
            assertTrue(response.head.startsWith("HTTP/1.1 206"), "partial content: ${response.head.lineSequence().first()}")
            assertTrue(response.head.contains("Content-Range: bytes 0-9/100"), "range header passed")
            assertTrue(!response.head.contains("Set-Cookie"), "cookie dropped")
            assertEquals(List(10) { it.toByte() }, response.body.toList(), "body streamed")
            val sent = upstream.requests.single()
            assertEquals("https://$authority/Videos/1/stream?static=true&api_key=k", sent.url.toString(), "only the pinned authority is contacted")
            assertEquals("bytes=0-9", sent.headers[HttpHeaders.Range], "range forwarded")
            assertEquals(null, sent.headers[HttpHeaders.Cookie], "cookie not forwarded")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `large bodies stream through intact`() = runTest {
        val big = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        val upstream = Upstream { Triple(HttpStatusCode.OK, mapOf("Content-Length" to listOf(big.size.toString())), big) }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val response = exchange(session.port, get(session, "/Videos/1/stream"))
            assertEquals(big.size, response.body.size, "all bytes")
            assertTrue(big.contentEquals(response.body), "identical")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `head returns headers without a body`() = runTest {
        val upstream = Upstream { Triple(HttpStatusCode.OK, mapOf("Content-Length" to listOf("100")), ByteArray(0)) }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val response = exchange(session.port, "HEAD /${session.token}/v HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n\r\n")
            assertTrue(response.head.startsWith("HTTP/1.1 200"), "ok")
            assertEquals(0, response.body.size, "no body")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `the relay is not an open proxy`() = runTest {
        val upstream = Upstream { Triple(HttpStatusCode.OK, emptyMap(), ByteArray(1)) }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            assertTrue(exchange(session.port, "GET /wrong/x HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n\r\n").head.startsWith("HTTP/1.1 404"), "wrong token")
            assertTrue(exchange(session.port, "GET https://evil.example/x HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n\r\n").head.startsWith("HTTP/1.1 400"), "absolute form")
            assertTrue(exchange(session.port, "POST /${session.token}/x HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n\r\n").head.startsWith("HTTP/1.1 405"), "post")
            assertTrue(exchange(session.port, "GET /${session.token}/x HTTP/1.1\r\nHost: evil.example\r\n\r\n").head.startsWith("HTTP/1.1 403"), "foreign host header")
            assertTrue(upstream.requests.isEmpty(), "nothing reached the upstream")
            exchange(session.port, get(session, "/..//evil.example/x"))
            assertTrue(upstream.requests.single().url.toString().startsWith("https://$authority/"), "the target host is never taken from the request")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `a redirect off the authority is refused`() = runTest {
        val upstream = Upstream { Triple(HttpStatusCode.Found, mapOf("Location" to listOf("https://evil.example/x")), ByteArray(0)) }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val response = exchange(session.port, get(session, "/Videos/1/stream"))
            assertTrue(response.head.startsWith("HTTP/1.1 502"), "bad gateway")
            assertTrue(!response.head.contains("evil.example"), "no foreign location leaked")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `an upstream failure is a bad gateway without details`() = runTest {
        val upstream = Upstream { error("boom https://secret.example/?api_key=k") }
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val response = exchange(session.port, get(session, "/Videos/1/stream"))
            assertTrue(response.head.startsWith("HTTP/1.1 502"), "502")
            assertTrue(!response.head.contains("secret") && response.body.isEmpty(), "no cause echoed")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `urls of the session authority map to loopback and others do not`() = runTest {
        val session = PinnedMediaRelay(Upstream { Triple(HttpStatusCode.OK, emptyMap(), ByteArray(0)) }).open(authority)
        try {
            assertEquals("http://127.0.0.1:${session.port}/${session.token}/Videos/1/stream?x=1", session.rewrite("https://$authority/Videos/1/stream?x=1"), "mapped")
            assertEquals(null, session.rewrite("https://other.example:8920/Videos/1"), "other host")
            assertEquals(null, session.rewrite("http://$authority/Videos/1"), "not https")
            assertEquals(32, session.token.length, "128-bit token")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `disposing the session closes the listener`() = runTest {
        val session = PinnedMediaRelay(Upstream { Triple(HttpStatusCode.OK, emptyMap(), ByteArray(0)) }).open(authority)
        val port = session.port
        session.dispose()
        val selector = SelectorManager(Dispatchers.Default)
        try {
            assertFailsWith<Throwable> { withContext(Dispatchers.Default) { withTimeout(5_000) { aSocket(selector).tcp().connect("127.0.0.1", port) } } }
        } finally {
            selector.close()
        }
    }
}
