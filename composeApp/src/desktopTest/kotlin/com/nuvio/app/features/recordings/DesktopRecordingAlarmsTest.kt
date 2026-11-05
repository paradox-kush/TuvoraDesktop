package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.internal.DesktopRecordingAlarms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRecordingAlarmsTest {
    private val base = 1_700_000_000_000L

    /** A wall clock that follows virtual time, plus a settable jump (the user changing the system clock). */
    private class Harness(scope: TestScope, base: Long) {
        var jumpMs = 0L
        val fired = mutableListOf<String>()
        var onFire: suspend (String) -> Unit = {}
        val now: () -> Long = { base + scope.currentTime + jumpMs }
        val alarms = DesktopRecordingAlarms(scope.backgroundScope, wallClock = now, onFire = { id -> fired += id; onFire(id) })
    }

    @Test
    fun `an alarm is exact and fires once at its time after chunked sleeps`() = runTest {
        val h = Harness(this, base)
        assertEquals(ArmKind.EXACT, h.alarms.arm("a", base + 150_000))
        advanceTimeBy(149_999); testScheduler.runCurrent()
        assertTrue(h.fired.isEmpty())
        advanceTimeBy(2); testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
        advanceTimeBy(10 * 60_000); testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
        assertEquals(0, h.alarms.armedCount())
    }

    @Test
    fun `arming the same id again replaces the timer and never stacks`() = runTest {
        val h = Harness(this, base)
        h.alarms.arm("a", base + 100_000)
        h.alarms.arm("a", base + 100_000)
        h.alarms.arm("a", base + 200_000)
        assertEquals(1, h.alarms.armedCount())
        advanceTimeBy(150_000); testScheduler.runCurrent()
        assertTrue(h.fired.isEmpty(), "the earlier timers were replaced")
        advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
    }

    @Test
    fun `disarm cancels the timer`() = runTest {
        val h = Harness(this, base)
        h.alarms.arm("a", base + 10_000)
        h.alarms.arm("b", base + 10_000)
        h.alarms.disarm("a")
        assertEquals(1, h.alarms.armedCount())
        advanceTimeBy(20_000); testScheduler.runCurrent()
        assertEquals(listOf("b"), h.fired)
        h.alarms.disarm("never-armed") // no error
    }

    @Test
    fun `a time already past fires at once`() = runTest {
        val h = Harness(this, base)
        h.alarms.arm("a", base - 5_000)
        testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
    }

    @Test
    fun `the wall clock set forward fires within one chunk`() = runTest {
        val h = Harness(this, base)
        h.alarms.arm("a", base + 3_600_000)
        advanceTimeBy(30_000); testScheduler.runCurrent()
        h.jumpMs = 3_600_000 // the user (or NTP, or a DST fix) moves the clock an hour ahead
        advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
    }

    @Test
    fun `the wall clock set back does not fire early`() = runTest {
        val h = Harness(this, base)
        h.alarms.arm("a", base + 600_000)
        advanceTimeBy(120_000); testScheduler.runCurrent()
        h.jumpMs = -3_600_000
        advanceTimeBy(600_000); testScheduler.runCurrent()
        assertTrue(h.fired.isEmpty())
        h.jumpMs = 0
        advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(listOf("a"), h.fired)
    }

    @Test
    fun `disarm and arm from inside the fire do not cancel the running fire`() = runTest {
        val h = Harness(this, base)
        var finished = false
        h.onFire = { id ->
            h.alarms.disarm(id) // e.g. cancel(id) of a running capture
            h.alarms.arm(id, h.now() + 60_000) // e.g. an early wake re-arming itself
            delay(5_000) // the capture keeps running
            finished = true
        }
        h.alarms.arm("a", base + 1_000)
        advanceTimeBy(2_000); testScheduler.runCurrent()
        assertEquals(1, h.alarms.armedCount(), "the re-arm made a new timer")
        advanceTimeBy(6_000); testScheduler.runCurrent()
        assertTrue(finished, "the fire was not cancelled by its own disarm")
    }

    @Test
    fun `exact times are always honoured on desktop`() = runTest {
        assertTrue(Harness(this, base).alarms.exactAllowed())
    }
}
