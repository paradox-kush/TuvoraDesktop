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
        assertEquals(Route.Party, NativePartyControlsPolicy.routePlaybackRequest(partySynced = true))
    }

    @Test
    fun `outside a party the native layer keeps running playback itself`() {
        playbackActions.forEach { action ->
            assertEquals(Route.Native, NativePartyControlsPolicy.routeAction(action, partySynced = false), action.name)
        }
        assertEquals(Route.Native, NativePartyControlsPolicy.routePlaybackRequest(partySynced = false))
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

    @Test
    fun `back closes the party panel before it leaves the player`() {
        // The native X and Escape both send Back; with the panel open that must not tear the player down under it.
        assertTrue(NativePartyControlsPolicy.backClosesPartyPanel(showPartyPanel = true))
        assertFalse(NativePartyControlsPolicy.backClosesPartyPanel(showPartyPanel = false))
    }

    @Test
    fun `the native controls offer Watch party only when it is available and unlocked`() {
        assertTrue(NativePartyControlsPolicy.partyEntryVisible(available = true, controlsLocked = false))
        assertFalse(NativePartyControlsPolicy.partyEntryVisible(available = false, controlsLocked = false))
        assertFalse(NativePartyControlsPolicy.partyEntryVisible(available = true, controlsLocked = true))
    }
}
