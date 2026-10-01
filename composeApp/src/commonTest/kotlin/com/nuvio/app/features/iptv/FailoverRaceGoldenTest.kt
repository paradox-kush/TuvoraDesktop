package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** kotlin.test runner for [FailoverRaceGolden] (assertEquals(expected, actual, message) order). */
class FailoverRaceGoldenTest {

    @Test
    fun `stagger table`() {
        for (c in FailoverRaceGolden.staggerCases) {
            assertEquals(c.expectedMs, FailoverStagger.compute(c.stats, c.nowMs), c.name)
        }
    }

    @Test
    fun `stats update table`() {
        for (case in FailoverRaceGolden.statsSteps) {
            var stats = case.initial
            for ((i, step) in case.steps.withIndex()) {
                val before = stats
                stats = when (step.op) {
                    "win" -> FailoverLatencyStats.onWin(stats, step.arg, step.nowMs)
                    "fail" -> FailoverLatencyStats.onFailure(stats, step.nowMs)
                    else -> error("unknown op ${step.op}")
                }
                assertEquals(step.expected, stats, "${case.name} (step $i)")
                if (step.expected == before && before != null) {
                    assertSame(before, stats, "${case.name} (step $i): an unchanged record is the SAME instance (no prefs rewrite)")
                }
            }
        }
    }

    @Test
    fun `xtream probe validity table`() {
        for (c in FailoverRaceGolden.xtreamProbeCases) {
            assertEquals(ProbeVerdict.valueOf(c.expected), FailoverProbePolicy.xtreamLogin(c.body), c.name)
        }
    }

    @Test
    fun `m3u probe validity table`() {
        for (c in FailoverRaceGolden.m3uProbeCases) {
            assertEquals(ProbeVerdict.valueOf(c.expected), FailoverProbePolicy.m3uPrefix(c.body), c.name)
        }
    }

    @Test
    fun `stalker probe validity table`() {
        for ((token, expected) in FailoverRaceGolden.stalkerProbeCases) {
            assertEquals(ProbeVerdict.valueOf(expected), FailoverProbePolicy.stalkerHandshake(token), "token=$token")
        }
    }

    @Test
    fun `probe verdicts map to the failures the classifier understands`() {
        assertNull(FailoverProbePolicy.toFailure(ProbeVerdict.VALID, "x"))
        val invalid = FailoverProbePolicy.toFailure(ProbeVerdict.INVALID, "x")!!
        val definitive = FailoverProbePolicy.toFailure(ProbeVerdict.DEFINITIVE, "x")!!
        assertTrue(FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(invalid)), "invalid response fails over")
        assertFalse(FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(definitive)), "auth rejected never fails over")
    }

    @Test
    fun `host key table`() {
        for ((url, host) in FailoverRaceGolden.hostKeyCases) assertEquals(host, FailoverHostKey.of(url), url)
    }

    @Test
    fun `race scenarios in virtual time`() {
        for (s in FailoverRaceGolden.scenarios) {
            val got = FailoverRaceGolden.simulate(s)
            val e = s.expected
            assertEquals(e.winner, got.winner, "${s.name}: winner")
            assertEquals(e.definitive, got.definitive, "${s.name}: definitive")
            assertEquals(e.decidedAtMs, got.decidedAtMs, "${s.name}: decided at")
            assertEquals(e.startedAtMs, got.startedAtMs, "${s.name}: start times (absent = never started)")
            assertEquals(e.cancelled, got.cancelled, "${s.name}: cancelled")
            assertEquals(e.maxInFlight, got.maxInFlight, "${s.name}: max in flight")
            assertEquals(e.surface, got.surface, "${s.name}: surfaced server")
        }
    }

    @Test
    fun `persisted state json table - old states load and unknown keys are ignored`() {
        val json = Json { ignoreUnknownKeys = true }
        for (c in FailoverRaceGolden.persistenceCases) {
            assertEquals(c.expected, json.decodeFromString(ServerFailoverState.serializer(), c.json), c.name)
        }
    }

    @Test
    fun `a state without stats still encodes exactly as Step 0_3 wrote it`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            """{"activeIndex":1,"mainRetryAfterMs":123}""",
            json.encodeToString(ServerFailoverState.serializer(), ServerFailoverState(1, 123)),
        )
    }

    /** Invariants over 3000 random worlds: the rules hold whatever the servers do. */
    @Test
    fun `scheduler invariants hold for random servers`() {
        val rnd = Random(20261001)
        repeat(3_000) { n ->
            val count = rnd.nextInt(2, 7)
            val hosts = (0 until count).associateWith { "h${rnd.nextInt(0, 4)}" }
            val behaviors = (0 until count).associateWith {
                val after = rnd.nextLong(1, 3_000)
                when (rnd.nextInt(5)) {
                    0 -> FailoverRaceGolden.Behavior.Hang(rnd.nextLong(20_000, 61_000))
                    1 -> FailoverRaceGolden.Behavior.Fail(after)
                    2 -> FailoverRaceGolden.Behavior.Ok(after)
                    3 -> FailoverRaceGolden.Behavior.Invalid(after)
                    else -> FailoverRaceGolden.Behavior.Definitive(after)
                }
            }
            val staggers = (0 until count).associateWith { rnd.nextLong(200, 2_501) }
            val s = FailoverRaceGolden.Scenario("random $n", "", (0 until count).toList(), behaviors, staggers, hosts,
                FailoverRaceGolden.Expected(null, decidedAtMs = 0, startedAtMs = emptyMap(), maxInFlight = 0))
            val got = FailoverRaceGolden.simulate(s)
            val msg = "world $n $behaviors hosts=$hosts staggers=$staggers -> $got"
            assertTrue(got.maxInFlight <= FailoverRace.MAX_IN_FLIGHT, "never more than ${FailoverRace.MAX_IN_FLIGHT} counted in flight: $msg")
            assertFalse(got.hostOverlap, "never two concurrent attempts against one host: $msg")
            if (got.winner != null) {
                assertTrue(got.winner in got.startedAtMs.keys, "the winner was started: $msg")
                assertFalse(got.winner in got.cancelled, "the winner is not a cancelled loser: $msg")
                assertTrue(got.startedAtMs.values.all { it <= got.decidedAtMs }, "nothing starts after the decision: $msg")
            } else {
                assertEquals(0, got.surface ?: if (got.startedAtMs.keys.contains(0)) 0 else -1, "main's error is surfaced when it ran: $msg")
            }
        }
    }
}
