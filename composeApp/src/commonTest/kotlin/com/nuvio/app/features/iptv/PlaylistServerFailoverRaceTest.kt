package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.addons.awaitingLocally
import com.nuvio.app.features.addons.failoverConnectTimeoutMs
import com.nuvio.app.features.addons.signalHttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.nuvio.app.features.iptv.FailoverRaceGolden.Behavior

/**
 * Step 0.3b — the staggered failover EXECUTOR in virtual time (`runTest`), driven by the same golden
 * scenarios as the pure scheduler, plus the executor-only guarantees: one big request at most, losers
 * cancelled without side effects, definitive answers surfaced, structured concurrency.
 */
class PlaylistServerFailoverRaceTest {

    private lateinit var store: InMemoryServerFailoverStateStore
    private var wallNow = 1_000_000L

    @BeforeTest
    fun setUp() {
        store = InMemoryServerFailoverStateStore()
    }

    @AfterTest
    fun tearDown() = PlaylistServerFailover.resetForTest()

    private fun accountOf(count: Int, hosts: Map<Int, String> = emptyMap()): XtreamAccount {
        fun url(i: Int) = "http://${hosts[i] ?: "s$i.test"}:${1000 + i}"
        return XtreamAccount(
            id = "race|u", name = "P", baseUrl = url(0), username = "u", password = "p",
            backupUrls = (1 until count).map(::url),
        )
    }

    private fun TestScope.install() {
        PlaylistServerFailover.installForTest(store = store, clock = { wallNow }, raceClock = { testScheduler.currentTime })
    }

    private fun indexOf(acc: XtreamAccount, a: XtreamAccount) = PlaylistServerFailover.servers(acc).indexOf(a.baseUrl)

    /** A fake panel world: every server follows a [Behavior]; everything it does is recorded. */
    private inner class World(val acc: XtreamAccount, val behaviors: Map<Int, Behavior>, val realAfterProbeMs: Long = 100) {
        val startedAt = LinkedHashMap<Int, Long>()
        val raceStarts = LinkedHashMap<Int, String>()            // server -> "real" | "probe"
        val cancelled = LinkedHashSet<Int>()
        val active = LinkedHashSet<Int>()
        var bodyReads = 0
        var realRequests = 0
        var probeRequests = 0
        var maxCounted = 0
        var hostOverlap = false
        val connectTimeouts = mutableListOf<Long?>()
        var scope: TestScope? = null

        private fun begin(index: Int, kind: String) {
            val now = scope!!.testScheduler.currentTime
            if (kind != "followup") {
                startedAt[index] = now
                raceStarts[index] = kind
            }
            val host = FailoverHostKey.of(PlaylistServerFailover.servers(acc)[index])
            if (active.any { FailoverHostKey.of(PlaylistServerFailover.servers(acc)[it]) == host }) hostOverlap = true
            active += index
            maxCounted = maxOf(maxCounted, active.count { it !in startedAt || now - startedAt.getValue(it) < FailoverRace.HANG_CUTOFF_MS })
        }

        private suspend fun behave(index: Int, b: Behavior, real: Boolean): String {
            try {
                when (b) {
                    is Behavior.Hang -> { delay(b.failsAfterMs); throw HttpStatusException(504, "timeout s$index") }
                    is Behavior.Fail -> { delay(b.afterMs); throw HttpStatusException(503, "refused s$index") }
                    is Behavior.Invalid -> { delay(b.afterMs); throw FailoverInvalidResponseException("html s$index") }
                    is Behavior.Definitive -> {
                        delay(b.afterMs)
                        throw if (real) HttpStatusException(401, "401 s$index") else FailoverAuthRejectedException("auth=0 s$index")
                    }
                    is Behavior.Ok -> {
                        delay(b.afterMs)
                        if (real) { signalHttpHeaders(); bodyReads++ }
                        return "real:$index"
                    }
                    is Behavior.Queued -> {
                        awaitingLocally { delay(b.queuedMs) }
                        return behave(index, b.then, real)
                    }
                }
            } catch (c: CancellationException) {
                cancelled += index
                throw c
            } finally {
                active -= index
            }
        }

        val probe: suspend (XtreamAccount) -> Unit = { a ->
            val i = indexOf(acc, a)
            probeRequests++
            connectTimeouts += failoverConnectTimeoutMs()
            begin(i, "probe")
            behave(i, behaviors.getValue(i), real = false)
        }

        val attempt: suspend (XtreamAccount) -> String = { a ->
            val i = indexOf(acc, a)
            connectTimeouts += failoverConnectTimeoutMs()
            realRequests++
            if (i in startedAt || raceStarts.isNotEmpty() && raceStarts[i] != null) {
                // the follow-up real request on a probe's winner
                begin(i, "followup")
                try {
                    delay(realAfterProbeMs)
                    signalHttpHeaders()
                    bodyReads++
                    "real:$i"
                } finally {
                    active -= i
                }
            } else {
                begin(i, "real")
                behave(i, behaviors.getValue(i), real = true)
            }
        }
    }

    private fun staggerStats(scenario: FailoverRaceGolden.Scenario): ServerFailoverState {
        val stats = scenario.staggers.mapValues { (_, ms) ->
            if (ms == FailoverStagger.RECENT_FAILURE_MS) ServerLatencyStats(lastFailAtMs = wallNow - 1_000)
            else ServerLatencyStats(ewmaMs = (ms / FailoverStagger.EWMA_FACTOR).toLong(), samples = 3, lastSampleAtMs = wallNow - 1_000)
        }
        return ServerFailoverState(stats = stats)
    }

    @Test
    fun `every golden scenario plays out identically on the executor`() {
        for (scenario in FailoverRaceGolden.scenarios) {
            runTest {
                store = InMemoryServerFailoverStateStore()
                install()
                val acc = accountOf(scenario.order.size, scenario.hosts)
                store.write(1, acc.id, staggerStats(scenario))
                val world = World(acc, scenario.behaviors).also { it.scope = this }
                val outcome = runCatching { PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt) }
                val e = scenario.expected
                val label = scenario.name
                val elapsed = testScheduler.currentTime
                when {
                    e.winner == null -> {
                        assertTrue(outcome.isFailure, "$label: gives up")
                        assertEquals(e.decidedAtMs, elapsed, "$label: gave up at")
                        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("s${e.surface}"), "$label: surfaces s${e.surface}'s error, got ${outcome.exceptionOrNull()}")
                    }
                    e.definitive -> {
                        assertTrue(outcome.isFailure, "$label: surfaces the definitive answer")
                        assertTrue(outcome.exceptionOrNull() !is CancellationException, label)
                        assertEquals(e.decidedAtMs, elapsed, "$label: surfaced at")
                        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("s${e.winner}"), label)
                    }
                    else -> {
                        assertEquals("real:${e.winner}", outcome.getOrNull(), "$label: served by (${outcome.exceptionOrNull()})")
                        val total = e.decidedAtMs + if (e.winner == scenario.order.first()) 0 else world.realAfterProbeMs
                        assertEquals(total, elapsed, "$label: total virtual time")
                    }
                }
                assertEquals(e.startedAtMs, world.startedAt.toMap(), "$label: attempt start times")
                assertEquals(e.cancelled, world.cancelled, "$label: cancelled losers")
                assertEquals(e.maxInFlight, world.maxCounted, "$label: max counted in flight")
                assertFalse(world.hostOverlap, "$label: never two concurrent attempts on one host")
                assertTrue(world.active.isEmpty(), "$label: nothing left running (no leaked coroutine)")
                assertTrue(world.bodyReads <= 1, "$label: at most one big body ever read (was ${world.bodyReads})")
                PlaylistServerFailover.resetForTest()
            }
        }
    }

    // --- a: the common path ---------------------------------------------------------------

    @Test
    fun `a healthy main makes exactly one request and no probe`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Ok(120))).also { it.scope = this }
        val served = PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt)
        assertEquals("real:0", served)
        assertEquals(1, world.realRequests, "exactly one request")
        assertEquals(0, world.probeRequests, "no probe")
        assertEquals(1, world.bodyReads)
        assertEquals(120, testScheduler.currentTime)
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
    }

    @Test
    fun `a healthy active backup inside its window is one request to the backup and nothing to main`() = runTest {
        install()
        val acc = accountOf(3)
        store.write(1, acc.id, ServerFailoverState(activeIndex = 1, mainRetryAfterMs = wallNow + 60_000))
        val world = World(acc, mapOf(1 to Behavior.Ok(90))).also { it.scope = this }
        assertEquals("real:1", PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt))
        assertEquals(1, world.realRequests)
        assertEquals(0, world.probeRequests)
        assertEquals(setOf(1), world.startedAt.keys)
    }

    // --- c: cancelled losers are not failures ---------------------------------------------

    @Test
    fun `a hung main that loses the race is not marked failed and does not trip the breaker`() = runTest {
        install()
        val acc = accountOf(2)
        val mainUrl = acc.baseUrl
        PlaylistServerFailover.run(
            acc,
            probe = { a -> delay(150) },
            attempt = { a ->
                if (a.baseUrl == mainUrl) {
                    // The real production shape: the request runs inside the breaker's guard.
                    IptvPanelGuard.guard.guardedPanelRequest(a.baseUrl) { awaitCancellation() }
                } else { delay(100); a.baseUrl }
            },
        )
        val state = store.read(1, acc.id)
        assertEquals(1, state.activeIndex, "the winner becomes active")
        assertNull(state.stats[0]?.lastFailAtMs, "a cancelled loser is not a failure: no lastFailAt")
        // Hammering main's admission after the cancel must still be Allowed (a counted failure would open it at 2).
        repeat(3) { assertIs<PanelAdmission.Allowed>(IptvPanelGuard.guard.admit(mainUrl)) }
    }

    @Test
    fun `a failed main IS marked failed so its successor starts almost at once next time`() = runTest {
        install()
        val acc = accountOf(2)
        val world = World(acc, mapOf(0 to Behavior.Fail(20), 1 to Behavior.Ok(50))).also { it.scope = this }
        PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt)
        val stats = store.read(1, acc.id).stats
        assertEquals(wallNow, stats[0]?.lastFailAtMs, "main failed (fail-over-able): recorded")
        assertEquals(FailoverStagger.RECENT_FAILURE_MS, FailoverStagger.compute(stats[0], wallNow))
        assertNull(stats[1]?.lastFailAtMs)
        assertEquals(50L, stats[1]?.ewmaMs, "the winner's time-to-valid became its first sample")
    }

    // --- f / m: definitive answers ---------------------------------------------------------

    @Test
    fun `a 401 from the first responder is surfaced immediately and nothing else is started`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Definitive(80))).also { it.scope = this }
        val e = assertFailsWith<HttpStatusException> { PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt) }
        assertEquals(401, e.status)
        assertEquals(80, testScheduler.currentTime)
        assertEquals(setOf(0), world.startedAt.keys)
        assertEquals(ServerFailoverState(), store.read(1, acc.id), "a definitive answer changes nothing")
    }

    @Test
    fun `a probe that says auth=0 surfaces at once without failing over and cancels the hung main`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Definitive(150), 2 to Behavior.Ok(10))).also { it.scope = this }
        assertFailsWith<FailoverAuthRejectedException> { PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt) }
        assertEquals(1_650, testScheduler.currentTime)
        assertEquals(setOf(0, 1), world.startedAt.keys, "backup 2 never starts")
        assertEquals(setOf(0), world.cancelled)
        assertEquals(0, store.read(1, acc.id).activeIndex)
    }

    // --- k: a parked domain never wins -----------------------------------------------------

    @Test
    fun `a probe that gets 200 HTML does not win and the next server is tried`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Fail(20), 1 to Behavior.Invalid(60), 2 to Behavior.Ok(40))).also { it.scope = this }
        assertEquals("real:2", PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt))
        assertEquals(2, PlaylistServerFailover.activeIndex(acc))
        assertTrue(store.read(1, acc.id).stats[1]?.lastFailAtMs != null, "the parked domain is remembered as failed")
    }

    // --- h: one big request ever -----------------------------------------------------------

    @Test
    fun `probe plus real is at most one big body even when two attempts answer at the same instant`() = runTest {
        install()
        val acc = accountOf(2)
        // Main's real headers and backup 1's valid probe both land at t=1650.
        val world = World(acc, mapOf(0 to Behavior.Ok(1_650), 1 to Behavior.Ok(150))).also { it.scope = this }
        val served = PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt)
        assertTrue(served == "real:0" || served == "real:1", served)
        assertEquals(1, world.bodyReads, "the loser's body is never read: exactly one big body in total")
        assertTrue(world.active.isEmpty())
    }

    // --- a winner whose real request then fails continues the race --------------------------

    @Test
    fun `a valid probe whose real request then fails continues with the remaining servers`() = runTest {
        install()
        val acc = accountOf(3)
        val tried = mutableListOf<String>()
        val served = PlaylistServerFailover.run(
            acc,
            probe = { a -> if (a.baseUrl == acc.baseUrl) throw HttpStatusException(503, "main down") else delay(30) },
            attempt = { a ->
                tried += a.baseUrl
                when (a.baseUrl) {
                    acc.baseUrl -> throw HttpStatusException(503, "main down")
                    acc.backupUrls[0] -> throw HttpStatusException(500, "catalog broke on b1")
                    else -> a.baseUrl
                }
            },
        )
        assertEquals(acc.backupUrls[1], served)
        assertEquals(listOf(acc.baseUrl, acc.backupUrls[0], acc.backupUrls[1]), tried, "main, b1 (probe ok, catalog failed), b2")
        assertEquals(2, PlaylistServerFailover.activeIndex(acc))
    }

    @Test
    fun `when every server fails the main server's error is surfaced and the state is kept`() = runTest {
        install()
        val acc = accountOf(3)
        val mainErr = HttpStatusException(502, "main 502")
        val thrown = assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(
                acc,
                probe = { throw HttpStatusException(503, "probe down") },
                attempt = { a -> throw if (a.baseUrl == acc.baseUrl) mainErr else HttpStatusException(504, "other") },
            )
        }
        assertEquals(mainErr, thrown)
        assertEquals(0, store.read(1, acc.id).activeIndex)
    }

    // --- an attempt that delivered rows is never replayed ------------------------------------

    @Test
    fun `a real request that already delivered rows is not replayed on another server`() = runTest {
        install()
        val acc = accountOf(3)
        var delivered = false
        val probes = mutableListOf<String>()
        assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(
                acc,
                canRetry = { !delivered },
                probe = { a -> probes += a.baseUrl },
                attempt = { delivered = true; throw HttpStatusException(503, "mid-body") },
            )
        }
        assertTrue(probes.isEmpty(), "no probe started: the failure was not fail-over-able any more")
    }

    // --- connect timeout & structured concurrency -------------------------------------------

    @Test
    fun `every attempt of a failover walk asks for the 8 s connect timeout`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Ok(100))).also { it.scope = this }
        PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt)
        assertTrue(world.connectTimeouts.size >= 3, "real, probe, follow-up real")
        assertTrue(world.connectTimeouts.all { it == FailoverRace.CONNECT_TIMEOUT_MS }, "${world.connectTimeouts}")
    }

    @Test
    fun `caller cancellation tears every attempt down`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Hang(), 1 to Behavior.Hang(), 2 to Behavior.Hang())).also { it.scope = this }
        val outcome = runCatching {
            kotlinx.coroutines.withTimeout(5_000) { PlaylistServerFailover.run(acc, probe = world.probe, attempt = world.attempt) }
        }
        assertIs<TimeoutCancellationException>(outcome.exceptionOrNull())
        assertEquals(setOf(0, 1, 2), world.cancelled, "every in-flight attempt was cancelled with the caller")
        assertTrue(world.active.isEmpty())
        assertEquals(ServerFailoverState(), store.read(1, acc.id), "a cancelled walk records nothing")
    }

    @Test
    fun `without a probe the walk is sequential and never races`() = runTest {
        install()
        val acc = accountOf(3)
        val world = World(acc, mapOf(0 to Behavior.Fail(10), 1 to Behavior.Ok(30))).also { it.scope = this }
        assertEquals("real:1", PlaylistServerFailover.run(acc, attempt = world.attempt))
        assertEquals(0, world.probeRequests)
    }
}
