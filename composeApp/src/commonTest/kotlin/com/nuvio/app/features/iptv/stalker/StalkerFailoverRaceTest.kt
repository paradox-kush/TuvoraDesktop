package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.iptv.FailoverInvalidResponseException
import com.nuvio.app.features.iptv.InMemoryServerFailoverStateStore
import com.nuvio.app.features.iptv.PlaylistServerFailover
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.ServerFailoverPolicy
import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Step 0.3b — Stalker under a failover race (virtual time, fake portals):
 *  - a cancelled loser's half-built session is DISCARDED: no token left behind, no watchdog, and no
 *    re-handshake on the portal that lost (a Stalker handshake rotates the MAC's token);
 *  - per-portal single-flight survives racing: the winner's probe authenticates once and the real
 *    request reuses that token;
 *  - a portal that answers 200 HTML to the handshake is INVALID (fails over), never a winner.
 */
class StalkerFailoverRaceTest {

    private var now = 1_000L
    private lateinit var store: InMemoryServerFailoverStateStore
    private val log = mutableListOf<String>()

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
    private fun action(url: String) = Regex("[?&]action=([^&]*)").find(url)?.groupValues?.get(1)

    @BeforeTest
    fun setUp() {
        store = InMemoryServerFailoverStateStore()
    }

    @AfterTest
    fun tearDown() {
        PlaylistServerFailover.resetForTest()
        StalkerClient.sessionFactory = { StalkerSession(it) }
        StalkerClient.clearMemoryCachesForTest()
    }

    private fun TestScope.install() {
        PlaylistServerFailover.installForTest(store = store, clock = { now }, raceClock = { testScheduler.currentTime })
    }

    private fun account(tag: String) = XtreamAccount(
        id = "stalker|http://$tag-main.test|00:1A:79:00:00:01", name = "S", baseUrl = "http://$tag-main.test",
        username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:01",
        backupUrls = listOf("http://$tag-backup.test"),
    )

    /** A portal whose behaviour per (host, action) is scripted; every request is logged "host/action". */
    private fun portal(script: suspend (host: String, action: String?, nth: Int) -> String?): suspend (String, Map<String, String>) -> String {
        val counts = HashMap<String, Int>()
        return { url, _ ->
            val host = hostOf(url)
            val act = action(url)
            val key = "$host/$act"
            log += key
            val nth = (counts[key] ?: 0) + 1
            counts[key] = nth
            script(host, act, nth) ?: when (act) {
                "handshake" -> """{"js":{"token":"T-$host"}}"""
                "get_profile" -> """{"js":{"id":"1"}}"""
                "get_genres" -> """{"js":[{"id":"g1","title":"News"}]}"""
                "get_events" -> """{"js":{"data":{"msgs":0}}}"""
                else -> """{"js":[]}"""
            }
        }
    }

    @Test
    fun `a main whose handshake hangs loses to the backup - one handshake each and no re-mint on main`() = runTest {
        install()
        StalkerClient.sessionFactory = { StalkerSession(it, portal { host, act, _ ->
            if (host == "a-main.test" && act == "handshake") { delay(60_000); throw HttpStatusException(504, "timeout") } else null
        }) }
        val acc = account("a")

        assertTrue(StalkerClient.verify(acc).isSuccess, "the backup answers")
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        assertEquals(1, log.count { it == "a-main.test/handshake" }, "main: the one handshake that lost — never retried: $log")
        assertEquals(listOf("a-main.test/handshake"), log.filter { it.startsWith("a-main.test") }, "nothing else was sent to main: $log")
        // A first authentication is endpoint discovery + the real handshake (2 requests); the real request
        // that follows the winning probe reuses the token — single-flight, no third handshake.
        assertEquals(2, log.count { it == "a-backup.test/handshake" }, "single-flight: the probe authenticated, the real request reuses its token: $log")
        assertEquals(1, log.count { it == "a-backup.test/get_profile" }, log.toString())
        assertEquals(1, log.count { it == "a-backup.test/get_genres" }, log.toString())
    }

    @Test
    fun `a loser cancelled between handshake and profile leaves no half-built session behind`() = runTest {
        install()
        StalkerClient.sessionFactory = { StalkerSession(it, portal { host, act, nth ->
            // main: the handshake works, get_profile hangs the first time (this is the attempt that loses).
            if (host == "b-main.test" && act == "get_profile" && nth == 1) { delay(60_000); throw HttpStatusException(504, "timeout") } else null
        }) }
        val acc = account("b")

        assertTrue(StalkerClient.verify(acc).isSuccess)
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        val mainBefore = log.filter { it.startsWith("b-main.test") }
        assertEquals(listOf("b-main.test/handshake", "b-main.test/handshake", "b-main.test/get_profile"), mainBefore, "discovery, handshake, profile (hung): $log")

        // The window runs out; main is healthy again. Its session must start from scratch (an ordinary
        // first handshake) — a leftover token with an unfinished profile would instead read as "stale"
        // and re-mint, and a watchdog would already be pinging the portal.
        now += ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        log.clear()
        assertTrue(StalkerClient.verify(acc).isSuccess)
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
        assertEquals(
            listOf("b-main.test/handshake", "b-main.test/get_profile", "b-main.test/get_genres"),   // endpoint already known: no discovery
            log.filter { it.startsWith("b-main.test") }.filter { it != "b-main.test/get_events" },
            "a clean first authentication on main: $log",
        )
        assertEquals(0, log.count { it.startsWith("b-backup.test") }, "the backup is not touched when main answers: $log")
    }

    @Test
    fun `a portal that answers the handshake with html is invalid and fails over`() = runTest {
        install()
        StalkerClient.sessionFactory = { StalkerSession(it, portal { host, act, _ ->
            when {
                // main hangs; backup... is a parked domain: 200 HTML for everything.
                host == "c-main.test" && act == "handshake" -> { delay(60_000); throw HttpStatusException(504, "timeout") }
                host == "c-backup.test" -> "<html><body>parked</body></html>"
                else -> null
            }
        }) }
        val acc = account("c")
        val failure = StalkerClient.verify(acc).exceptionOrNull()
        assertTrue(failure != null, "nothing valid answered")
        assertEquals(0, PlaylistServerFailover.activeIndex(acc), "the parked domain never becomes active")
        assertTrue(log.none { it == "c-backup.test/get_genres" }, "no real request ever reached the parked domain: $log")
    }

    @Test
    fun `an authenticated backup session still proves it is alive before it wins`() = runTest {
        install()
        var backupAlive = true
        StalkerClient.sessionFactory = { StalkerSession(it, portal { host, act, _ ->
            when {
                host == "d-main.test" -> { delay(60_000); throw HttpStatusException(504, "timeout") }
                host == "d-backup.test" && !backupAlive -> throw HttpStatusException(503, "down")
                else -> null
            }
        }) }
        val acc = account("d")
        assertTrue(StalkerClient.verify(acc).isSuccess)      // main hangs, backup authenticates and answers
        log.clear()
        // Backup is now warm (token held) and then dies; main still hangs. The probe must NOT pass on the
        // cached token alone: it sends one harmless get_events ping (a handshake would rotate the token).
        backupAlive = false
        now += ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        assertFailsWith<HttpStatusException> { StalkerClient.verify(acc).getOrThrow() }
        assertTrue(log.contains("d-backup.test/get_events"), "warm probe pings instead of trusting the token: $log")
        assertTrue(log.none { it == "d-backup.test/handshake" }, "and never re-handshakes: $log")
    }

    @Test
    fun `an html get_events answer on a warm session reads as not a portal`() = runTest {
        val session = StalkerSession(account("e"), portal { _, act, _ -> if (act == "get_events") "<html>parked</html>" else null })
        try {
            session.probe()                       // first call: full authentication
            assertFailsWith<FailoverInvalidResponseException> { session.probe() }   // warm: ping -> HTML -> invalid
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `requests queued behind their own siblings on a healthy portal never start a backup probe`() = runTest {
        install()
        StalkerClient.sessionFactory = { StalkerSession(it, portal { host, act, _ ->
            // A healthy but not instant portal: every browse call takes 800 ms. The session lets 3 run at once.
            if (host == "g-main.test" && act == "get_genres") { delay(800); null } else null
        }) }
        val acc = account("g")
        // Authenticate first so the burst below is pure browse traffic.
        assertTrue(StalkerClient.verify(acc).isSuccess)
        log.clear()
        val burst = (1..9).map { async { StalkerClient.verify(acc) } }
        assertTrue(burst.all { it.await().isSuccess })
        // 9 calls through 3 permits = the last one waited ~1.6 s locally, longer than the 1.5 s stagger.
        assertTrue(testScheduler.currentTime >= 2_000, "the burst really did queue (took ${testScheduler.currentTime} ms)")
        assertEquals(0, log.count { it.startsWith("g-backup.test") }, "queue time is not portal latency - the backup is never contacted: $log")
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
    }
}
