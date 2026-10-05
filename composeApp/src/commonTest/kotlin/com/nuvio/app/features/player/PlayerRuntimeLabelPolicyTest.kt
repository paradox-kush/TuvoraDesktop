package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P1 (W2 device pass): fullscreen live showed "00:15 / 00:40" — the buffered window of a live
 * stream read as a film's runtime. Live shows the LIVE badge only; VOD and a catch-up replay
 * (a finite programme) keep their times.
 */
class PlayerRuntimeLabelPolicyTest {

    @Test
    fun liveStreamShowsNoRuntime() {
        assertNull(PlayerRuntimeLabelPolicy.label(isLive = true, positionMs = 15_000, durationMs = 40_000, showRemainingTime = false))
        assertNull(PlayerRuntimeLabelPolicy.label(isLive = true, positionMs = 15_000, durationMs = 40_000, showRemainingTime = true))
    }

    @Test
    fun vodAndReplayKeepElapsedAndTotal() {
        assertEquals(
            "00:15 / 00:40",
            PlayerRuntimeLabelPolicy.label(isLive = false, positionMs = 15_000, durationMs = 40_000, showRemainingTime = false),
        )
    }

    @Test
    fun vodAndReplayKeepRemainingTime() {
        assertEquals(
            "−00:25",
            PlayerRuntimeLabelPolicy.label(isLive = false, positionMs = 15_000, durationMs = 40_000, showRemainingTime = true),
        )
    }
}
