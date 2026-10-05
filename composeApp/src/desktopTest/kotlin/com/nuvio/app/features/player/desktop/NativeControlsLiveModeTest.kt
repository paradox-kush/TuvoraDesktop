package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P1 (W2 device pass, desktop port): the native control bar drew a seek bar and "00:15 / 00:40" on a
 * live channel. The bar now learns the stream is live from the controls state and shows LIVE.
 */
class NativeControlsLiveModeTest {

    @Test
    fun liveChannelTellsTheBarItIsLive() {
        val json = PlayerControlsState(title = "BBC One", isLive = true).toControlsJson(isFullscreen = false)
        assertTrue("\"isLive\":true" in json, json)
    }

    @Test
    fun vodAndReplayKeepTheirTimeline() {
        val json = PlayerControlsState(title = "Film").toControlsJson(isFullscreen = false)
        assertTrue("\"isLive\":false" in json, json)
    }
}
