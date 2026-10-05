package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1 (W2 device pass): fullscreen live showed "00:15 / 00:40" — the buffered window of a live
 * stream read as a film's runtime. Live shows the LIVE badge only; VOD and a catch-up replay
 * (a finite programme) keep their times.
 */
class PlayerRuntimeLabelPolicyTest {

    @Test
    fun liveStreamShowsNoRuntime() {
        assertFalse(PlayerRuntimeLabelPolicy.showsRuntime(isLive = true))
    }

    @Test
    fun vodAndReplayKeepTheirRuntime() {
        assertTrue(PlayerRuntimeLabelPolicy.showsRuntime(isLive = false))
        assertEquals("00:15 / 00:40", formatPlaybackRuntime(positionMs = 15_000, durationMs = 40_000, showRemainingTime = false))
        assertEquals("−00:25", formatPlaybackRuntime(positionMs = 15_000, durationMs = 40_000, showRemainingTime = true))
    }
}
