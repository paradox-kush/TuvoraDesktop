package com.nuvio.app.features.iptv

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UX20 — the Xtream "Add playlist" check driven over a REAL socket through the real desktop (OkHttp)
 * transport. A server that cannot be reached used to fail with "Authentication failed": the
 * user_info read swallowed every transport error and the auth check then ran on `null`. The
 * user was told their password was wrong when the address was.
 */
class PlaylistSaveErrorTransportTest {

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun account(baseUrl: String) = XtreamAccount(
        id = "$baseUrl|u", name = "p", baseUrl = baseUrl, username = "u", password = "p",
    )

    /** A localhost port with nothing listening — the connection is refused, never answered. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun serve(body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `an unreachable server fails with the connection error - not Authentication failed`() = runBlocking {
        val failure = assertNotNull(XtreamClient.verify(account("http://127.0.0.1:${closedPort()}")).exceptionOrNull())
        assertNotEquals("Authentication failed", failure.message, "a refused connection is not a credential rejection")
        val chain = generateSequence(failure) { it.cause }.toList()
        assertTrue(chain.any { it is ConnectException }, "the transport failure reaches the caller: $chain")
    }

    @Test
    fun `an unreachable server maps to the could-not-reach sentence`() = runBlocking {
        val failure = assertNotNull(XtreamClient.verify(account("http://127.0.0.1:${closedPort()}")).exceptionOrNull())
        kotlin.test.assertEquals(
            PlaylistSaveMessage.Known(PlaylistSaveError.UNREACHABLE),
            PlaylistSaveErrorPolicy.classify(failure, SOURCE_TYPE_XTREAM),
        )
    }

    @Test
    fun `a panel that answers auth 0 maps to wrong username or password`() = runBlocking {
        val base = serve("""{"user_info":{"auth":0}}""")
        val failure = assertNotNull(XtreamClient.verify(account(base)).exceptionOrNull())
        kotlin.test.assertEquals(
            PlaylistSaveMessage.Known(PlaylistSaveError.WRONG_CREDENTIALS),
            PlaylistSaveErrorPolicy.classify(failure, SOURCE_TYPE_XTREAM),
        )
    }

    @Test
    fun `JVM transport types map to the plain-language failures`() {
        assertTrue(classifyPlaylistTransportFailure(java.net.UnknownHostException("dead.invalid")) == PlaylistTransportFailure.UNREACHABLE)
        assertTrue(classifyPlaylistTransportFailure(java.net.SocketTimeoutException("timeout")) == PlaylistTransportFailure.UNREACHABLE)
        assertTrue(classifyPlaylistTransportFailure(ConnectException("refused")) == PlaylistTransportFailure.UNREACHABLE)
        assertTrue(classifyPlaylistTransportFailure(javax.net.ssl.SSLHandshakeException("bad cert")) == PlaylistTransportFailure.SECURE_CONNECTION)
        assertTrue(classifyPlaylistTransportFailure(java.net.MalformedURLException("no protocol")) == PlaylistTransportFailure.INVALID_ADDRESS)
        assertTrue(classifyPlaylistTransportFailure(IllegalStateException("parse")) == null, "a non-transport error is not classified here")
    }
}
