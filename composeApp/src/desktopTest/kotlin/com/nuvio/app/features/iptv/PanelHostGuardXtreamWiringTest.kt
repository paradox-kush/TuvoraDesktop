package com.nuvio.app.features.iptv

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * WP6 wiring — XtreamClient's panel requests run through the process-wide [IptvPanelGuard]
 * breaker. Driven over REAL sockets through the real Android transport: a port with nothing
 * listening refuses instantly and deterministically, so the java.net exception the classifier
 * must map ([java.net.ConnectException] -> CONNECTION_FAILURE) is the genuine article, not a
 * fake's guess.
 *
 * The proof that a fast-fail skipped the wire is the failure TYPE: a real attempt against a dead
 * socket always surfaces the transport's own error, while the breaker's refusal is the distinct
 * [PanelHostFastFailException]. Each test uses its own dead port so origins never collide across
 * tests sharing the process-wide guard.
 */
class PanelHostGuardXtreamWiringTest {

    /** A base URL with NOTHING listening: bind an ephemeral port, close it, use it. */
    private fun deadPanel(): XtreamAccount {
        val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        return XtreamAccount(id = baseUrl, name = "dead", baseUrl = baseUrl, username = "u", password = "p")
    }

    @Test
    fun `a dead panel fast fails the second browse`() = runBlocking<Unit> {
        val acc = deadPanel()
        // Browse one: the hub's category list + a row fetch — two real transport attempts, both
        // refused at the socket (never the breaker: the type proves the wire was tried).
        val first = XtreamClient.liveCategories(acc).exceptionOrNull()
        val second = XtreamClient.liveChannels(acc, null).exceptionOrNull()
        assertNotNull(first, "a dead socket must fail the first request")
        assertNotNull(second, "a dead socket must fail the second request")
        assertFalse(first is PanelHostFastFailException, "first attempt must go to the wire")
        assertFalse(second is PanelHostFastFailException, "second attempt must go to the wire")
        // Browse two: the breaker answers instead of the wire — no third transport attempt.
        val third = XtreamClient.vodCategories(acc).exceptionOrNull()
        assertIs<PanelHostFastFailException>(third, "the second browse must fast-fail")
    }

    @Test
    fun `a user retry resets the breaker and goes to the wire`() = runBlocking<Unit> {
        val acc = deadPanel()
        XtreamClient.liveCategories(acc)
        XtreamClient.liveCategories(acc)
        assertIs<PanelHostFastFailException>(
            XtreamClient.liveCategories(acc).exceptionOrNull(),
            "two connection failures must open the breaker",
        )
        // What every user-driven Retry affordance calls BEFORE its first request.
        IptvPanelGuard.resetForAccount(acc)
        val after = XtreamClient.liveCategories(acc).exceptionOrNull()
        assertNotNull(after, "the dead socket still fails the request")
        assertFalse(after is PanelHostFastFailException, "after a user reset the request must reach the wire")
    }
}
