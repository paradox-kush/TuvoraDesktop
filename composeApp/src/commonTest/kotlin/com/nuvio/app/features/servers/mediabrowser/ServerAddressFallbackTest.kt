package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.FixedServerHttp
import com.nuvio.app.features.servers.ServerCertificateException
import com.nuvio.app.features.servers.ServerCertificateRefusedException
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerHttp
import com.nuvio.app.features.servers.ServerPlainHttpException
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A server address typed without a scheme: https first, plain http only after a failure to CONNECT, never silently. */
class ServerAddressFallbackTest {
    private val info = """{"Id":"srv1","ServerName":"Den","Version":"10.10.0","ProductName":"Jellyfin Server"}"""
    private val auth = """{"AccessToken":"tok","ServerId":"srv1","User":{"Id":"u1","Name":"viewer","ServerId":"srv1"}}"""

    private class World(
        val httpsConnects: Boolean,
        val httpsStatus: Int = 200,
        val refused: Boolean = false,
        val offerable: Boolean = true,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.protocol.name == "https" && !httpsConnects) throw RuntimeException("connection refused")
                val body = if (request.url.encodedPath.endsWith("/System/Info/Public")) {
                    """{"Id":"srv1","ServerName":"Den","Version":"10.10.0","ProductName":"Jellyfin Server"}"""
                } else {
                    """{"AccessToken":"tok","ServerId":"srv1","User":{"Id":"u1","Name":"viewer","ServerId":"srv1"}}"""
                }
                val status = if (request.url.protocol.name == "https") httpsStatus else 200
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val http: ServerHttp = object : ServerHttp {
            override fun clientFor(url: String): HttpClient = client
            override fun certificateFailure(url: String): ServerCertificateException? =
                if (refused && offerable && url.startsWith("https://")) ServerCertificateException("10.0.2.2:18443", "self-signed", "AA:BB", false) else null
            override fun certificateRefused(url: String): Boolean = refused && url.startsWith("https://")
        }
        val schemes get() = requests.map { it.url.protocol.name }
    }

    @Test
    fun `https answering is used and no plain http request is ever made`() = runTest {
        val world = World(httpsConnects = true)
        val signIn = JellyfinProvider(world.http).signIn("10.0.2.2:18443", "viewer", "pw")
        assertEquals("https://10.0.2.2:18443", signIn.address)
        assertTrue(world.schemes.all { it == "https" }, "schemes ${world.schemes}")
    }

    @Test
    fun `https that cannot connect asks before plain http and sends no secret`() = runTest {
        val world = World(httpsConnects = false)
        val failure = assertFailsWith<ServerPlainHttpException> { JellyfinProvider(world.http).signIn("10.0.2.2:8096", "viewer", "pw") }
        assertEquals("http://10.0.2.2:8096", failure.httpAddress)
        assertTrue(world.requests.none { it.method.value == "POST" }, "no credentials may travel before the user confirmed plain http")
        assertTrue(world.requests.any { it.url.protocol.name == "http" }, "the plain http probe reaches the server (public info only)")
    }

    @Test
    fun `a certificate error goes to the trust prompt and never to http`() = runTest {
        val world = World(httpsConnects = false, refused = true)
        assertFailsWith<ServerCertificateException> { JellyfinProvider(world.http).signIn("10.0.2.2:18443", "viewer", "pw") }
        assertTrue(world.schemes.none { it == "http" }, "schemes ${world.schemes}")
    }

    @Test
    fun `a refusal that cannot be trusted still never falls back to http`() = runTest {
        val world = World(httpsConnects = false, refused = true, offerable = false)
        assertFailsWith<ServerCertificateRefusedException> { JellyfinProvider(world.http).quickConnectStart("10.0.2.2:18443") }
        assertTrue(world.schemes.none { it == "http" }, "schemes ${world.schemes}")
    }

    @Test
    fun `an explicit http address is honoured and https is never tried`() = runTest {
        val world = World(httpsConnects = false)
        val signIn = JellyfinProvider(world.http).signIn("http://10.0.2.2:8096", "viewer", "pw")
        assertEquals("http://10.0.2.2:8096", signIn.address)
        assertTrue(world.schemes.none { it == "https" }, "schemes ${world.schemes}")
    }

    @Test
    fun `an https server that answers with an error stays on https`() = runTest {
        val world = World(httpsConnects = true, httpsStatus = 503)
        val failure = assertFailsWith<ServerException> { JellyfinProvider(world.http).quickConnectStart("nas.local") }
        assertEquals(ServerFailure.UNREACHABLE, failure.failure)
        assertTrue(world.schemes.none { it == "http" }, "schemes ${world.schemes}")
    }

    @Test
    fun `quick connect asks before plain http too`() = runTest {
        val world = World(httpsConnects = false)
        assertFailsWith<ServerPlainHttpException> { JellyfinProvider(world.http).quickConnectStart("10.0.2.2:8096") }
        assertTrue(world.requests.none { it.method.value == "POST" }, "no Initiate before the user confirmed")
    }
}
