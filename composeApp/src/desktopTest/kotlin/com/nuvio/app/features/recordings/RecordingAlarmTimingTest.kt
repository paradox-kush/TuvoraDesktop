package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingAlarmTiming
import com.nuvio.app.features.recordings.internal.RecordingAlarmTiming.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingAlarmTimingTest {
    @Test
    fun `a fire time that has come or passed fires now`() {
        assertEquals(Step.FireNow, RecordingAlarmTiming.next(nowMs = 1_000, fireAtMs = 1_000))
        assertEquals(Step.FireNow, RecordingAlarmTiming.next(nowMs = 9_000, fireAtMs = 1_000))
    }

    @Test
    fun `a far fire time sleeps one chunk at most`() {
        assertEquals(Step.Sleep(60_000), RecordingAlarmTiming.next(nowMs = 0, fireAtMs = 3_600_000))
        assertEquals(Step.Sleep(60_000), RecordingAlarmTiming.next(nowMs = 0, fireAtMs = 60_001))
    }

    @Test
    fun `the last stretch sleeps exactly what is left`() {
        assertEquals(Step.Sleep(60_000), RecordingAlarmTiming.next(nowMs = 0, fireAtMs = 60_000))
        assertEquals(Step.Sleep(1), RecordingAlarmTiming.next(nowMs = 999, fireAtMs = 1_000))
        assertEquals(Step.Sleep(12_345), RecordingAlarmTiming.next(nowMs = 100, fireAtMs = 12_445))
    }

    @Test
    fun `a clock set forward is answered on the next wake and one set back just waits longer`() {
        // Slept to 10:00 for a 10:30 fire; the user moves the clock to 10:45 -> fire.
        assertEquals(Step.FireNow, RecordingAlarmTiming.next(nowMs = 45 * 60_000L, fireAtMs = 30 * 60_000L))
        // Moved back an hour -> still waiting, but never more than a chunk at a time.
        assertEquals(Step.Sleep(60_000), RecordingAlarmTiming.next(nowMs = -30 * 60_000L, fireAtMs = 30 * 60_000L))
    }

    @Test
    fun `the chunk is configurable`() {
        assertEquals(Step.Sleep(50), RecordingAlarmTiming.next(nowMs = 0, fireAtMs = 500, maxChunkMs = 50))
    }
}
