package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.QuickConnectPoll
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.emby.EmbyProvider
import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import com.nuvio.app.features.servers.supports
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Endpoints checked against the Jellyfin 12.2.0 OpenAPI (GET Enabled, POST Initiate, GET Connect?secret, POST Users/AuthenticateWithQuickConnect). */
class QuickConnectProviderTest {
    private val info = """{"Id":"srv1","ServerName":"Den","Version":"10.10.0","ProductName":"Jellyfin Server"}"""
    private val auth = """{"AccessToken":"tok","ServerId":"srv1","User":{"Id":"u1","Name":"viewer","ServerId":"srv1"}}"""

    private fun http(connect: String = """{"Authenticated":false,"Secret":"sec","Code":"123456"}""", connectStatus: Boolean = true) =
        TestHttp { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/System/Info/Public") -> info
                path.endsWith("/QuickConnect/Enabled") -> "true"
                path.endsWith("/QuickConnect/Initiate") -> """{"Authenticated":false,"Secret":"sec","Code":"123456"}"""
                path.endsWith("/QuickConnect/Connect") -> connect
                path.endsWith("/Users/AuthenticateWithQuickConnect") -> auth
                else -> error("unexpected ${request.url}")
            }
        }

    @Test
    fun `jellyfin supports quick connect and emby does not`() {
        assertTrue(JellyfinProvider(http().client).supports(ServerCapability.QUICK_CONNECT), "jellyfin")
        assertFalse(EmbyProvider(http().client).supports(ServerCapability.QUICK_CONNECT), "emby has no quick connect")
    }

    @Test
    fun `availability asks the enabled endpoint`() = runTest {
        val http = http()
        assertTrue(JellyfinProvider(http.client).quickConnectAvailable("http://jf.local:8096"), "enabled")
        assertTrue(http.requests.any { it.method.value == "GET" && it.url.encodedPath.endsWith("/QuickConnect/Enabled") }, "GET Enabled")
    }

    @Test
    fun `start posts initiate and returns the code and secret`() = runTest {
        val http = http()
        val challenge = JellyfinProvider(http.client).quickConnectStart("jf.local:8096")
        assertEquals("123456", challenge.code, "code")
        assertEquals("sec", challenge.secret, "secret")
        assertTrue(http.requests.any { it.method.value == "POST" && it.url.encodedPath.endsWith("/QuickConnect/Initiate") }, "POST Initiate")
    }

    @Test
    fun `poll maps authenticated and pending`() = runTest {
        val pending = JellyfinProvider(http().client)
        val challenge = pending.quickConnectStart("jf.local:8096")
        assertEquals(QuickConnectPoll.PENDING, pending.quickConnectPoll(challenge), "not yet")
        val approved = JellyfinProvider(http(connect = """{"Authenticated":true,"Secret":"sec","Code":"123456"}""").client)
        assertEquals(QuickConnectPoll.AUTHORISED, approved.quickConnectPoll(approved.quickConnectStart("jf.local:8096")), "approved")
    }

    private fun statusHttp(connectStatus: Int, initiateStatus: Int = 200) = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val (status, body) = when {
                path.endsWith("/System/Info/Public") -> 200 to info
                path.endsWith("/QuickConnect/Initiate") -> initiateStatus to """{"Secret":"sec","Code":"123456"}"""
                path.endsWith("/QuickConnect/Connect") -> connectStatus to "{}"
                else -> error("unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun `an unknown secret is expired`() = runTest {
        val provider = JellyfinProvider(statusHttp(connectStatus = 404))
        val challenge = provider.quickConnectStart("jf.local:8096")
        assertEquals(QuickConnectPoll.EXPIRED, provider.quickConnectPoll(challenge), "404 on Connect")
    }

    @Test
    fun `a disabled quick connect is reported as unauthorised`() = runTest {
        val provider = JellyfinProvider(statusHttp(connectStatus = 200, initiateStatus = 401))
        val failure = assertFailsWith<ServerException> { provider.quickConnectStart("jf.local:8096") }
        assertEquals(ServerFailure.AUTH_REQUIRED, failure.failure, "401 on Initiate")
    }

    @Test
    fun `complete exchanges the secret for this devices own token`() = runTest {
        val http = http()
        val provider = JellyfinProvider(http.client)
        val signIn = provider.quickConnectComplete(provider.quickConnectStart("jf.local:8096"))
        assertEquals("srv1", signIn.serverId, "server id")
        assertEquals("u1", signIn.userId, "user id")
        assertEquals("tok", signIn.token, "token")
        val exchange = http.requests.last { it.url.encodedPath.endsWith("/Users/AuthenticateWithQuickConnect") }
        assertEquals("POST", exchange.method.value, "POST")
        assertTrue(exchange.text.contains("\"Secret\":\"sec\""), "body carries the secret")
    }

    @Test
    fun `emby refuses quick connect`() = runTest {
        assertFailsWith<ServerException> { EmbyProvider(http().client).quickConnectStart("emby.local:8096") }
    }
}
