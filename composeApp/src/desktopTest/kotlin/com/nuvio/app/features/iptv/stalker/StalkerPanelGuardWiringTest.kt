package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.PanelAdmission
import com.nuvio.app.features.iptv.PanelHostFastFailException
import com.nuvio.app.features.iptv.PanelHostGuard
import com.nuvio.app.features.iptv.PanelRequestOutcome
import com.nuvio.app.features.iptv.XtreamAccount
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WP6 wiring — every [StalkerSession] portal request is admitted through the injected
 * [PanelHostGuard], connection failures open the breaker, an open breaker fast-fails with the
 * policy's own distinct exception, a user reset goes back to the wire, and the endpoint-probe
 * ladder carries the discovery exemption.
 *
 * Same harness as [StalkerSessionReauthTest]: the injectable [httpGet] seam plays the portal, so
 * transport attempts can be counted exactly — the fast-fail assertions are "the attempt counter
 * did not move", not just an exception type. The guard is injected fresh per test with a manual
 * clock, so tests neither share breaker records nor wait out real windows.
 */
class StalkerPanelGuardWiringTest {

    private var now = 0L
    private val guard = PanelHostGuard { now }

    private val portalBase = "http://portal.test"

    private fun account() = XtreamAccount(
        id = "t", name = "portal", baseUrl = portalBase,
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    /**
     * Fake portal that bootstraps happily (probe handshake, handshake, profile, watchdog ping)
     * and serves content until [dead] flips — after which every attempt throws the same
     * java.net.ConnectException a refused socket produces. Counts every transport attempt.
     */
    private class FakePortal {
        val attempts = AtomicInteger(0)
        val handshakes = AtomicInteger(0)
        var dead = false

        val seam: suspend (String, Map<String, String>) -> String = { url, _ ->
            attempts.incrementAndGet()
            if (dead) throw ConnectException("Connection refused")
            when (Regex("action=([^&]+)").find(url)?.groupValues?.get(1)) {
                "handshake" -> """{"js":{"token":"T${handshakes.incrementAndGet()}"}}"""
                "get_profile" -> """{"js":{"watchdog_timeout":120}}"""
                else -> """{"js":[]}"""
            }
        }
    }

    private val genres = mapOf("type" to "itv", "action" to "get_genres")

    @Test
    fun `a dead panel fast fails the second browse`() = runBlocking<Unit> {
        val portal = FakePortal()
        val session = StalkerSession(account(), portal.seam, panelGuard = guard)
        // Healthy bootstrap + first browse: the panel was alive, the breaker record is clear.
        session.request(genres)
        portal.dead = true
        // Browse one against the now-dead panel: two real transport attempts, both refused.
        val first = runCatching { session.request(genres) }.exceptionOrNull()
        val second = runCatching { session.request(genres) }.exceptionOrNull()
        assertIs<ConnectException>(first, "first attempt must go to the wire")
        assertIs<ConnectException>(second, "second attempt must go to the wire")
        val attemptsBefore = portal.attempts.get()
        // Browse two: the breaker answers — the transport is NOT tried a third time.
        val third = runCatching { session.request(genres) }.exceptionOrNull()
        assertIs<PanelHostFastFailException>(third, "the second browse must fast-fail")
        assertEquals(attemptsBefore, portal.attempts.get(), "a fast-fail must not touch the transport")
    }

    @Test
    fun `a user retry resets the breaker and goes to the wire`() = runBlocking<Unit> {
        val portal = FakePortal()
        val session = StalkerSession(account(), portal.seam, panelGuard = guard)
        session.request(genres)
        portal.dead = true
        runCatching { session.request(genres) }
        runCatching { session.request(genres) }
        assertIs<PanelHostFastFailException>(
            runCatching { session.request(genres) }.exceptionOrNull(),
            "two connection failures must open the breaker",
        )
        // What the Retry affordances call (IptvPanelGuard.resetForAccount -> guard.reset) BEFORE
        // their first request — against the same normalized portal base the session admits with.
        guard.reset(StalkerProtocol.normalizePortalBase(portalBase))
        val attemptsBefore = portal.attempts.get()
        val after = runCatching { session.request(genres) }.exceptionOrNull()
        assertNotNull(after, "the dead portal still fails the request")
        assertFalse(after is PanelHostFastFailException, "after a user reset the request must reach the wire")
        assertTrue(portal.attempts.get() > attemptsBefore, "the reset attempt must reach the transport")
    }

    @Test
    fun `discovery probes bypass an open breaker`() = runBlocking<Unit> {
        // Open the breaker for the portal's origin before the session ever bootstraps.
        fun failOnce() {
            val allowed = guard.admit(portalBase) as PanelAdmission.Allowed
            guard.report(allowed, PanelRequestOutcome.CONNECTION_FAILURE)
        }
        failOnce()
        failOnce()
        assertIs<PanelAdmission.FastFail>(guard.admit(portalBase), "precondition: the breaker is open")
        // A fresh session's endpoint-probe ladder must still reach the portal (discovery flag),
        // and its SUCCESS must clear the record so the rest of the bootstrap proceeds.
        val portal = FakePortal()
        val session = StalkerSession(account(), portal.seam, panelGuard = guard)
        session.request(genres)   // would throw a fast-fail if discovery were blocked
        assertTrue(portal.handshakes.get() > 0, "the probe must have reached the portal")
        assertIs<PanelAdmission.Allowed>(guard.admit(portalBase), "the probe's success must clear the record")
    }
}
