package com.nuvio.app.features.livetv

import com.nuvio.app.features.player.PersistedPlayerTrackPreference
import com.nuvio.app.features.player.PictureChoice
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.VideoZoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F28 on phone/tablet (+ B123): fullscreen Live TV uses the regular player's controls with the live
 * keep/drop list, and finally has aspect/zoom — the phone live player was hard-wired to Fit. Same
 * button vectors as TV's LiveControlsPolicyTest.
 */
class LiveFullscreenControlsPolicyTest {

    private val channels = listOf("a", "b", "c")

    @Test
    fun `retry only on an error and audio only when there is a choice and zap only with a lineup`() {
        val healthy = LiveFullscreenControlsPolicy.buttons(failed = false, subtitleTracks = 0, audioTracks = 1, channelCount = 3)
        assertFalse(healthy.retry, "no retry while healthy")
        assertFalse(healthy.subtitles, "no subtitles button without subtitles")
        assertFalse(healthy.audio, "one audio track is no choice")
        assertTrue(healthy.channelZap, "a lineup can be zapped")
        val failedOneOff = LiveFullscreenControlsPolicy.buttons(failed = true, subtitleTracks = 2, audioTracks = 2, channelCount = 1)
        assertTrue(failedOneOff.retry, "retry on error")
        assertTrue(failedOneOff.subtitles, "subtitles")
        assertTrue(failedOneOff.audio, "audio choice")
        assertFalse(failedOneOff.channelZap, "one channel: nothing to zap to")
    }

    @Test
    fun `channel up and down step through the lineup and wrap`() {
        assertEquals("b", LiveFullscreenControlsPolicy.neighbour(channels, "a", +1))
        assertEquals("c", LiveFullscreenControlsPolicy.neighbour(channels, "a", -1), "wraps backwards")
        assertEquals("a", LiveFullscreenControlsPolicy.neighbour(channels, "c", +1), "wraps forwards")
        assertNull(LiveFullscreenControlsPolicy.neighbour(listOf("a"), "a", +1), "nowhere to go")
        assertEquals("a", LiveFullscreenControlsPolicy.neighbour(channels, "gone", +1), "a channel no longer listed starts at the top")
    }

    @Test
    fun `a channel opens with its own remembered picture else the global aspect and no zoom`() {
        val stored = PersistedPlayerTrackPreference(resizeMode = PlayerResizeMode.Zoom.name, zoomScaleX = 1.2f, zoomScaleY = 1.2f)
        assertEquals(
            PictureChoice(PlayerResizeMode.Zoom, VideoZoom(scaleX = 1.2f, scaleY = 1.2f)),
            LiveFullscreenControlsPolicy.initialPicture(rememberEnabled = true, stored = stored, globalResizeMode = PlayerResizeMode.Fit),
            "remembered for this channel",
        )
        assertEquals(
            PictureChoice(PlayerResizeMode.Fill, VideoZoom.IDENTITY),
            LiveFullscreenControlsPolicy.initialPicture(rememberEnabled = false, stored = stored, globalResizeMode = PlayerResizeMode.Fill),
            "remembering off: the global aspect, no zoom",
        )
    }

    @Test
    fun `the aspect button follows the desktop player's own cycle Fit then Zoom then Stretch`() {
        val fit = PictureChoice(PlayerResizeMode.Fit, VideoZoom.IDENTITY)
        val zoom = LiveFullscreenControlsPolicy.nextAspect(fit)
        assertEquals(PlayerResizeMode.Zoom, zoom.resizeMode)
        val stretch = LiveFullscreenControlsPolicy.nextAspect(zoom)
        assertEquals(PlayerResizeMode.Stretch, stretch.resizeMode)
        assertEquals(PlayerResizeMode.Fit, LiveFullscreenControlsPolicy.nextAspect(stretch).resizeMode)
    }
}
