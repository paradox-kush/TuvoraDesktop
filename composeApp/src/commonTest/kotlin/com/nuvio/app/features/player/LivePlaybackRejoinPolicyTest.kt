package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the foreground-return rejoin decision (moved out of CatchUpPlayback in S10b). */
class LivePlaybackRejoinPolicyTest {

    @Test
    fun `live and not catch-up rejoins the live edge`() {
        assertTrue(LivePlaybackRejoinPolicy.rejoinsLiveEdge("live", isCatchUpPlayback = false))
        assertTrue(LivePlaybackRejoinPolicy.rejoinsLiveEdge("  LIVE  ", isCatchUpPlayback = false))
    }

    @Test
    fun `catch-up never rejoins even when carried as a live stream`() {
        assertFalse(LivePlaybackRejoinPolicy.rejoinsLiveEdge("live", isCatchUpPlayback = true))
    }

    @Test
    fun `non-live never rejoins`() {
        assertFalse(LivePlaybackRejoinPolicy.rejoinsLiveEdge("movie", isCatchUpPlayback = false))
        assertFalse(LivePlaybackRejoinPolicy.rejoinsLiveEdge(null, isCatchUpPlayback = false))
    }
}
