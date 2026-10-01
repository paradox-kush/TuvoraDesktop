package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerChromeVisibilityPolicyTest {
    private fun visible(
        controls: Boolean = false,
        parentalGuide: Boolean = false,
        streamInfo: Boolean = false,
        locked: Boolean = false,
        pip: Boolean = false,
    ) = PlayerChromeVisibilityPolicy.shellVisible(controls, parentalGuide, streamInfo, locked, pip)

    @Test
    fun visibleControlsComposeTheShell() {
        assertTrue(visible(controls = true))
    }

    @Test
    fun nothingToShowHidesTheShell() {
        assertFalse(visible())
    }

    @Test
    fun parentalGuideOutlivesTheControlsAutoHide() {
        assertTrue(visible(parentalGuide = true))
    }

    // B105: auto-hide used to tear the readout out mid-animation, so it replayed over the top bar
    // on every reveal of the controls instead of playing once.
    @Test
    fun streamInfoOutlivesTheControlsAutoHide() {
        assertTrue(visible(streamInfo = true), "stream info must finish its one-shot animation")
    }

    @Test
    fun lockedControlsHideEverything() {
        assertFalse(visible(controls = true, parentalGuide = true, streamInfo = true, locked = true))
    }

    @Test
    fun pictureInPictureHidesEverything() {
        assertFalse(visible(controls = true, parentalGuide = true, streamInfo = true, pip = true))
    }
}
