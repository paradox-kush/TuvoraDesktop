package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.LivePauseNativeControls.Intent
import com.nuvio.app.features.livetv.pause.LivePauseState
import com.nuvio.app.features.player.PlayerControlsAction
import kotlin.test.Test
import kotlin.test.assertEquals

class LivePauseNativeControlsTest {
    private fun shifted(start: Long = 100_000, end: Long = 160_000, pos: Long = 140_000, paused: Boolean = true) =
        LivePauseState.Shifted(paused, (end - pos).coerceAtLeast(0), start, end, pos, false)

    // --- what the native seek bar is told ----------------------------------------------------

    @Test
    fun `no pause session shows no rewind bar`() {
        assertEquals(LivePauseNativeControls.Rewind.NONE, LivePauseNativeControls.rewind(LivePauseState.Off))
    }

    @Test
    fun `a window the viewer can scrub in shows the rewind bar bounded by it`() {
        assertEquals(
            LivePauseNativeControls.Rewind(true, 100_000, 160_000),
            LivePauseNativeControls.rewind(shifted()),
        )
    }

    @Test
    fun `window bounds are quantised inward so the bar is not re-pushed every tick and never offers a missing second`() {
        // start rounds UP to the next 5 s, end rounds DOWN.
        assertEquals(
            LivePauseNativeControls.Rewind(true, 105_000, 160_000),
            LivePauseNativeControls.rewind(shifted(start = 101_200, end = 163_900, pos = 150_000)),
        )
    }

    @Test
    fun `a pause with no window (provider archive) or a sliver of one shows no rewind bar`() {
        assertEquals(LivePauseNativeControls.Rewind.NONE, LivePauseNativeControls.rewind(shifted(start = 0, end = 0, pos = 0)))
        assertEquals(LivePauseNativeControls.Rewind.NONE, LivePauseNativeControls.rewind(shifted(start = 100_000, end = 103_000, pos = 103_000)))
    }

    // --- what the native layer's events mean --------------------------------------------------

    @Test
    fun `the native play pause request carries its target and is honoured with or without a session`() {
        for (type in listOf("setPlaybackState", "setPlaybackStateQuiet")) {
            assertEquals(Intent.Pause, LivePauseNativeControls.forEvent(type, 0.0, LivePauseState.Off), "$type 0 at live")
            assertEquals(Intent.Play, LivePauseNativeControls.forEvent(type, 1.0, LivePauseState.Off), "$type 1 at live")
            assertEquals(Intent.Play, LivePauseNativeControls.forEvent(type, 1.0, shifted()), "$type 1 shifted")
            assertEquals(Intent.Pause, LivePauseNativeControls.forEvent(type, 0.0, shifted(paused = false)), "$type 0 shifted")
        }
    }

    @Test
    fun `the go live chip only means something while shifted`() {
        assertEquals(Intent.GoLive, LivePauseNativeControls.forEvent(LivePauseNativeControls.GO_LIVE_EVENT, 0.0, shifted()))
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forEvent(LivePauseNativeControls.GO_LIVE_EVENT, 0.0, LivePauseState.Off))
    }

    @Test
    fun `unrelated native events are left to the existing handlers`() {
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forEvent("cursorActivity", 0.0, shifted()))
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forEvent("keepChromeVisible", 0.0, LivePauseState.Off))
    }

    @Test
    fun `the keyboard toggle toggles`() {
        assertEquals(Intent.Toggle, LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardTogglePlayback, LivePauseState.Off))
        assertEquals(Intent.Toggle, LivePauseNativeControls.forAction(PlayerControlsAction.TogglePlayback, shifted()))
    }

    @Test
    fun `left and right scrub ten seconds inside the window and stop at its ends`() {
        val s = shifted(start = 100_000, end = 160_000, pos = 140_000)
        assertEquals(Intent.Seek(130_000), LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekBack, s))
        assertEquals(Intent.Seek(130_000), LivePauseNativeControls.forAction(PlayerControlsAction.SeekBack, s))
        assertEquals(Intent.Seek(150_000), LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekForward, s))
        assertEquals(
            Intent.Seek(100_000),
            LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekBack, shifted(pos = 104_000)),
        )
        assertEquals(
            Intent.Seek(160_000),
            LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekForward, shifted(pos = 157_000)),
        )
    }

    @Test
    fun `without a session the arrow keys keep the engine's own seek`() {
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekBack, LivePauseState.Off))
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekForward, LivePauseState.Off))
        // A window-less pause (provider archive) has nothing to scrub either.
        assertEquals(
            Intent.Unhandled,
            LivePauseNativeControls.forAction(PlayerControlsAction.KeyboardSeekBack, shifted(start = 0, end = 0, pos = 0)),
        )
    }

    @Test
    fun `a finished scrub inside a session seeks the window and otherwise is left alone`() {
        assertEquals(Intent.Seek(120_000), LivePauseNativeControls.forScrubFinish(120_000, shifted()))
        assertEquals(Intent.Unhandled, LivePauseNativeControls.forScrubFinish(120_000, LivePauseState.Off))
    }
}
