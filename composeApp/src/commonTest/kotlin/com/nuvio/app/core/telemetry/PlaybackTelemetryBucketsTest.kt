package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.PlaybackTelemetryBuckets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTelemetryBucketsTest {
    @Test
    fun `ttff boundaries are exclusive upper bounds`() {
        val cases = listOf(
            -5L to "lt250", 0L to "lt250", 249L to "lt250", 250L to "lt500", 499L to "lt500", 500L to "lt1000",
            999L to "lt1000", 1_000L to "lt2000", 1_999L to "lt2000", 2_000L to "lt4000", 3_999L to "lt4000",
            4_000L to "lt8000", 7_999L to "lt8000", 8_000L to "ge8000", 600_000L to "ge8000",
        )
        for ((ms, bucket) in cases) assertEquals(bucket, PlaybackTelemetryBuckets.ttff(ms), "ttff $ms ms")
    }

    @Test
    fun `stall boundaries`() {
        val cases = listOf(
            0L to "lt2000", 1_999L to "lt2000", 2_000L to "lt5000", 4_999L to "lt5000", 5_000L to "lt15000",
            14_999L to "lt15000", 15_000L to "lt45000", 44_999L to "lt45000", 45_000L to "ge45000", 999_999L to "ge45000",
        )
        for ((ms, bucket) in cases) assertEquals(bucket, PlaybackTelemetryBuckets.stall(ms), "stall $ms ms")
    }

    @Test
    fun `multiview duration boundaries`() {
        val cases = listOf(0L to "lt30", 29L to "lt30", 30L to "lt120", 119L to "lt120", 120L to "lt600", 599L to "lt600", 600L to "ge600")
        for ((s, bucket) in cases) assertEquals(bucket, PlaybackTelemetryBuckets.durationS(s), "duration $s s")
    }

    @Test
    fun `ram class boundaries use binary gigabytes and leave an unknown total out`() {
        assertNull(PlaybackTelemetryBuckets.ramClass(null), "unknown")
        assertNull(PlaybackTelemetryBuckets.ramClass(0L), "zero is not a measurement")
        assertEquals("lt1_2", PlaybackTelemetryBuckets.ramClass(1_228L))
        assertEquals("lt2", PlaybackTelemetryBuckets.ramClass(1_229L))
        assertEquals("lt2", PlaybackTelemetryBuckets.ramClass(2_047L))
        assertEquals("lt3", PlaybackTelemetryBuckets.ramClass(2_048L))
        assertEquals("lt3", PlaybackTelemetryBuckets.ramClass(3_071L))
        assertEquals("ge3", PlaybackTelemetryBuckets.ramClass(3_072L))
        assertEquals("ge3", PlaybackTelemetryBuckets.ramClass(65_536L))
    }

    @Test
    fun `resolution class boundaries`() {
        assertEquals("unknown", PlaybackTelemetryBuckets.resClass(null))
        assertEquals("unknown", PlaybackTelemetryBuckets.resClass(0))
        assertEquals("sd", PlaybackTelemetryBuckets.resClass(576))
        assertEquals("sd", PlaybackTelemetryBuckets.resClass(719))
        assertEquals("hd720", PlaybackTelemetryBuckets.resClass(720))
        assertEquals("hd720", PlaybackTelemetryBuckets.resClass(1_079))
        assertEquals("fhd", PlaybackTelemetryBuckets.resClass(1_080))
        assertEquals("fhd", PlaybackTelemetryBuckets.resClass(2_159))
        assertEquals("uhd", PlaybackTelemetryBuckets.resClass(2_160))
    }

    @Test
    fun `every bucket a function can return is in its published list`() {
        val ttff = (0L..20_000L step 50L).map(PlaybackTelemetryBuckets::ttff).toSet()
        assertEquals(PlaybackTelemetryBuckets.TTFF.toSet(), ttff, "ttff")
        val stall = (0L..100_000L step 500L).map(PlaybackTelemetryBuckets::stall).toSet()
        assertEquals(PlaybackTelemetryBuckets.STALL.toSet(), stall, "stall")
    }
}
