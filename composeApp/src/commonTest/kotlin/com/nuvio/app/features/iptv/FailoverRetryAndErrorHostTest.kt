package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Step 0.3b leftovers that share the failover code:
 *  (a1) the hub's Retry resets the circuit breakers of ALL of a playlist's hosts, not just main;
 *  (a2) the hub error card names the server that actually failed last.
 */
class FailoverRetryAndErrorHostTest {

    private val acc = XtreamAccount(
        id = "retry|u", name = "P", baseUrl = "http://retry-main.test:80", username = "u", password = "p",
        backupUrls = listOf("http://retry-b1.test:8080", "http://retry-b2.test"),
    )

    @BeforeTest fun setUp() = PlaylistServerFailover.installForTest(clock = { 5_000L })
    @AfterTest fun tearDown() = PlaylistServerFailover.resetForTest()

    /** Two counted failures, the second admitted after the first was recorded (siblings are not a streak). */
    private suspend fun openBreaker(url: String) {
        repeat(2) {
            val a = IptvPanelGuard.guard.admit(url)
            if (a is PanelAdmission.Allowed) IptvPanelGuard.guard.report(a, PanelRequestOutcome.CONNECTION_FAILURE)
            delay(3)   // the guard runs on a real monotonic clock
        }
    }

    @Test
    fun `retry clears the breaker of main and of every backup`() = runBlocking {
        val urls = IptvPanelGuard.panelOriginUrlsOf(acc)
        assertEquals(listOf("http://retry-main.test:80", "http://retry-b1.test:8080", "http://retry-b2.test"), urls)
        for (u in urls) openBreaker(u)
        for (u in urls) assertIs<PanelAdmission.FastFail>(IptvPanelGuard.guard.admit(u), "precondition: $u's breaker is open")
        IptvPanelGuard.resetForAccount(acc)
        for (u in urls) assertIs<PanelAdmission.Allowed>(IptvPanelGuard.guard.admit(u), "$u is admitted after Retry")
    }

    @Test
    fun `a stalker playlist resets the normalized portal bases of every portal`() {
        val stalker = acc.copy(sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:01")
        val urls = IptvPanelGuard.panelOriginUrlsOf(stalker)
        assertEquals(3, urls.size)
        assertEquals(com.nuvio.app.features.iptv.stalker.StalkerProtocol.normalizePortalBase("http://retry-b1.test:8080"), urls[1])
    }

    @Test
    fun `an m3u playlist has no panel hosts to reset`() {
        assertEquals(emptyList(), IptvPanelGuard.panelOriginUrlsOf(acc.copy(sourceType = SOURCE_TYPE_M3U_URL, backupUrls = listOf("http://x.test/a.m3u"))))
    }

    @Test
    fun `with every server down the card names the main server and the next success clears it`() = runBlocking {
        assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(acc) { a -> throw HttpStatusException(503, a.baseUrl) }
        }
        assertEquals("http://retry-main.test:80", PlaylistServerFailover.lastFailedServerUrl(acc))
        PlaylistServerFailover.run(acc) { "ok" }
        assertNull(PlaylistServerFailover.lastFailedServerUrl(acc), "a success clears the failed host")
    }

    @Test
    fun `a definitive refusal from a backup names that backup and not main`() = runTest {
        PlaylistServerFailover.installForTest(clock = { 5_000L }, raceClock = { testScheduler.currentTime })
        assertFailsWith<FailoverAuthRejectedException> {
            PlaylistServerFailover.run(
                acc,
                probe = { a -> if (a.baseUrl == "http://retry-b1.test:8080") throw FailoverAuthRejectedException("auth=0") else delay(10) },
                attempt = { a -> delay(60_000); throw HttpStatusException(504, a.baseUrl) },   // main hangs
            )
        }
        assertEquals("http://retry-b1.test:8080", PlaylistServerFailover.lastFailedServerUrl(acc))
        // The hub turns that into the breadcrumb host.
        assertEquals("http://retry-b1.test:8080", IptvPanelGuard.panelOriginUrlOf(acc, PlaylistServerFailover.lastFailedServerUrl(acc)!!))
    }

    @Test
    fun `editing the server list forgets which server failed`() = runBlocking {
        assertFailsWith<HttpStatusException> { PlaylistServerFailover.run(acc) { a -> throw HttpStatusException(503, a.baseUrl) } }
        PlaylistServerFailover.reset(acc.id)
        assertNull(PlaylistServerFailover.lastFailedServerUrl(acc))
    }
}
