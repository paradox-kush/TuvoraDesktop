package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B208: desktop draws its controls in a native layer that auto-hides ITSELF and reports back with
 * `hideChrome`. A host that never records that hide keeps pushing `controlsVisible = true`, an
 * unchanged state the engine drops, so the later mouse-move (`cursorActivity`) can never re-show
 * the controls.
 */
class NativeControlsVisibilityPolicyTest {

    @Test
    fun `hideChrome from the native layer is recorded as hidden`() {
        assertEquals(false, NativeControlsVisibilityPolicy.next(visible = true, type = "hideChrome"))
    }

    @Test
    fun `mouse movement over the video re-shows hidden controls`() {
        assertEquals(true, NativeControlsVisibilityPolicy.next(visible = false, type = "cursorActivity"))
    }

    @Test
    fun `keepChromeVisible and toggleChrome from hidden show the controls`() {
        assertEquals(true, NativeControlsVisibilityPolicy.next(visible = false, type = "keepChromeVisible"))
        assertEquals(true, NativeControlsVisibilityPolicy.next(visible = false, type = "toggleChrome"))
    }

    @Test
    fun `toggleChrome from visible hides`() {
        assertEquals(false, NativeControlsVisibilityPolicy.next(visible = true, type = "toggleChrome"))
    }

    @Test
    fun `hide then move then hide round trips so each re-show is a real state change`() {
        var visible = true
        visible = NativeControlsVisibilityPolicy.next(visible, "hideChrome") ?: visible
        assertEquals(false, visible)
        visible = NativeControlsVisibilityPolicy.next(visible, "cursorActivity") ?: visible
        assertEquals(true, visible)
        visible = NativeControlsVisibilityPolicy.next(visible, "hideChrome") ?: visible
        assertEquals(false, visible)
        visible = NativeControlsVisibilityPolicy.next(visible, "cursorActivity") ?: visible
        assertEquals(true, visible)
    }

    @Test
    fun `unrelated events do not change visibility`() {
        assertNull(NativeControlsVisibilityPolicy.next(visible = false, type = "volumeChange"))
        assertNull(NativeControlsVisibilityPolicy.next(visible = true, type = "scrubChange"))
    }
}
