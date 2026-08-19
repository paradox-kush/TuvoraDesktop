package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals

class RadarClockTest {
    private class FakeClock(var now: Long) : RadarClock {
        override fun nowMs(): Long = now
        override fun formatTime(epochMs: Long): String = "t$epochMs"
        override fun dayLabel(epochMs: Long): String = "d$epochMs"
    }

    @Test
    fun `RadarRepository clock is injectable`() {
        val original = RadarRepository.clock
        try {
            val fake = FakeClock(123L)
            RadarRepository.clock = fake
            assertEquals(123L, RadarRepository.clock.nowMs(), "injected clock is read")
            fake.now = 456L
            assertEquals(456L, RadarRepository.clock.nowMs(), "clock advances deterministically")
        } finally {
            RadarRepository.clock = original
        }
    }
}
