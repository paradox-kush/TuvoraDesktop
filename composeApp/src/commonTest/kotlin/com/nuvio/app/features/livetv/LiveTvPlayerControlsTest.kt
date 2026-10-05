package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PlayerControlsAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvPlayerControlsTest {

    @Test
    fun `native fullscreen enters Live TV fullscreen instead of falling through to window fullscreen`() {
        val fullscreenChanges = mutableListOf<Boolean>()

        val handled = handleLiveTvPlayerControlsAction(
            action = PlayerControlsAction.ToggleFullscreen,
            fullscreen = false,
            setFullscreen = fullscreenChanges::add,
            onBack = {},
        )

        assertTrue(handled, "Live TV must consume the native command so desktop does not fullscreen the app window")
        assertEquals(listOf(true), fullscreenChanges)
    }

    @Test
    fun `native fullscreen exits Live TV fullscreen on second request`() {
        val fullscreenChanges = mutableListOf<Boolean>()

        val handled = handleLiveTvPlayerControlsAction(
            action = PlayerControlsAction.ToggleFullscreen,
            fullscreen = true,
            setFullscreen = fullscreenChanges::add,
            onBack = {},
        )

        assertTrue(handled)
        assertEquals(listOf(false), fullscreenChanges)
    }

    @Test
    fun `back exits layout fullscreen before leaving Live TV`() {
        val fullscreenChanges = mutableListOf<Boolean>()
        var backedOut = false

        val handled = handleLiveTvPlayerControlsAction(
            action = PlayerControlsAction.Back,
            fullscreen = true,
            setFullscreen = fullscreenChanges::add,
            onBack = { backedOut = true },
        )

        assertTrue(handled)
        assertEquals(listOf(false), fullscreenChanges)
        assertFalse(backedOut)
    }

    @Test
    fun `back leaves Live TV when already docked`() {
        var backedOut = false

        val handled = handleLiveTvPlayerControlsAction(
            action = PlayerControlsAction.Back,
            fullscreen = false,
            setFullscreen = {},
            onBack = { backedOut = true },
        )

        assertTrue(handled)
        assertTrue(backedOut)
    }

    @Test
    fun `the native aspect button cycles the live picture instead of doing nothing`() {
        // B123/F28 on desktop: the native bar always drew Resize, but Live TV did not answer it,
        // so the button was dead and the picture stayed Fit.
        var cycled = 0
        val handled = handleLiveTvPlayerControlsAction(
            action = PlayerControlsAction.ResizeMode,
            fullscreen = true,
            setFullscreen = {},
            onBack = {},
            onResizeMode = { cycled++ },
        )
        assertTrue(handled, "Live TV answers the native aspect button")
        assertEquals(1, cycled)
    }
}
