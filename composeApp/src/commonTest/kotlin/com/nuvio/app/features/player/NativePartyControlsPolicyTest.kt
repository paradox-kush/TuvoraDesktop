package com.nuvio.app.features.player

import com.nuvio.app.features.player.NativePartyControlsPolicy.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePartyControlsPolicyTest {
    private val playbackActions = listOf(
        PlayerControlsAction.TogglePlayback,
        PlayerControlsAction.KeyboardTogglePlayback,
        PlayerControlsAction.SeekBack,
        PlayerControlsAction.KeyboardSeekBack,
        PlayerControlsAction.SeekForward,
        PlayerControlsAction.KeyboardSeekForward,
        PlayerControlsAction.DoubleTapSeekBack,
        PlayerControlsAction.DoubleTapSeekForward,
    )

    @Test
    fun `in a watch party the native play pause and seek go through the party`() {
        // Otherwise a desktop member's pause never reaches the others and the drift correction undoes their seek.
        playbackActions.forEach { action ->
            assertEquals(Route.Party, NativePartyControlsPolicy.routeAction(action, partySynced = true), action.name)
        }
    }

    @Test
    fun `outside a party the native layer keeps running playback itself`() {
        playbackActions.forEach { action ->
            assertEquals(Route.Native, NativePartyControlsPolicy.routeAction(action, partySynced = false), action.name)
        }
    }

    @Test
    fun `volume and chrome actions never route through the party`() {
        listOf(
            PlayerControlsAction.KeyboardVolumeUp,
            PlayerControlsAction.KeyboardVolumeDown,
            PlayerControlsAction.ToggleChrome,
            PlayerControlsAction.Back,
        ).forEach { action ->
            assertEquals(Route.Native, NativePartyControlsPolicy.routeAction(action, partySynced = true), action.name)
        }
    }
}

class NativePartyPlaybackRejectionTest {
    @Test
    fun `a refused play or pause resyncs the native button`() {
        // A host-only guest pressing play while the party is paused: the runtime keeps shouldPlay false.
        assertTrue(NativePartyControlsPolicy.playbackRequestRefused(requestedPlay = true, resultingShouldPlay = false))
        assertTrue(NativePartyControlsPolicy.playbackRequestRefused(requestedPlay = false, resultingShouldPlay = true))
    }

    @Test
    fun `an honoured request needs no resync`() {
        assertFalse(NativePartyControlsPolicy.playbackRequestRefused(requestedPlay = true, resultingShouldPlay = true))
        assertFalse(NativePartyControlsPolicy.playbackRequestRefused(requestedPlay = false, resultingShouldPlay = false))
    }
}

class NativePartyServerTitleNoticeTest {
    @Test
    fun `desktop says not connected inside the player instead of a toast under the native video`() {
        assertTrue(NativePartyControlsPolicy.notConnectedNoticeInPlayer(isDesktop = true, notConnected = true))
    }

    @Test
    fun `phones keep the app toast and connected titles need no notice`() {
        assertFalse(NativePartyControlsPolicy.notConnectedNoticeInPlayer(isDesktop = false, notConnected = true))
        assertFalse(NativePartyControlsPolicy.notConnectedNoticeInPlayer(isDesktop = true, notConnected = false))
    }
}
