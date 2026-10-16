package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host-only watch party: the native play button flips optimistically on click (controls.js pendingIsPlaying) and
 * only fell back to the engine's real state 1.5 s later. When the runtime refuses the request (a guest may not play
 * the party), it bumps playbackResyncToken and the controls drop the optimistic state at once.
 */
class NativeControlsPlaybackResyncTest {
    @Test
    fun `the resync token reaches the native controls`() {
        val json = PlayerControlsState(playbackResyncToken = 3L).toControlsJson(isFullscreen = false)
        assertTrue("\"playbackResyncToken\":3" in json, json)
    }

    @Test
    fun `the controls drop the optimistic playing state when the token changes`() {
        val js = assertNotNull(javaClass.classLoader.getResource("player-ui/controls.js")).readText()
        val handler = Regex("""playbackResyncToken[\s\S]{0,600}""").findAll(js).map { it.value }
            .firstOrNull { "pendingIsPlaying = null" in it && "lastNativeIsPlaying" in it }
        assertNotNull(handler, "controls.js must reset pendingIsPlaying to the engine's state on a playbackResyncToken change")
    }
}
