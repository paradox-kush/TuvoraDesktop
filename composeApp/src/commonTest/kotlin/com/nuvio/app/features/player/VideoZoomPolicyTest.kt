package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoZoomPolicyTest {

    @Test
    fun stepsAreExactAndDoNotDrift() {
        var zoom = VideoZoom.IDENTITY
        repeat(7) { zoom = VideoZoomPolicy.adjust(zoom, VideoZoomAxis.Width, +1) }
        assertEquals(1.35f, zoom.scaleX)
        assertEquals(1f, zoom.scaleY)
        repeat(7) { zoom = VideoZoomPolicy.adjust(zoom, VideoZoomAxis.Width, -1) }
        assertTrue(zoom.isIdentity)
    }

    @Test
    fun bothAxesMoveTogetherAndClamp() {
        val zoomed = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.Both, +100)
        assertEquals(VideoZoomPolicy.MAX_SCALE, zoomed.scaleX)
        assertEquals(VideoZoomPolicy.MAX_SCALE, zoomed.scaleY)
        val shrunk = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.Height, -100)
        assertEquals(VideoZoomPolicy.MIN_SCALE, shrunk.scaleY)
    }

    @Test
    fun panIsClampedToHalfThePicture() {
        val panned = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.PanY, -100)
        assertEquals(-VideoZoomPolicy.MAX_PAN, panned.panY)
    }

    @Test
    fun nonFiniteStoredValuesNormalizeToIdentity() {
        val broken = VideoZoom(scaleX = Float.NaN, scaleY = Float.POSITIVE_INFINITY, panX = Float.NaN)
        // A corrupted stored value (NaN or infinite) means "no zoom", never an extreme picture.
        assertEquals(VideoZoom.IDENTITY, VideoZoomPolicy.normalize(broken))
    }

    @Test
    fun mpvPropertiesAreTheFullLocaleFreeSet() {
        val props = VideoZoomPolicy.mpvProperties(VideoZoom(scaleX = 1.33f, scaleY = 1f, panX = -0.04f, panY = 0.1f))
        assertEquals(
            listOf(
                "video-scale-x" to "1.33",
                "video-scale-y" to "1.00",
                "video-pan-x" to "-0.04",
                "video-pan-y" to "0.10",
            ),
            props,
        )
    }

    @Test
    fun identityMpvPropertiesResetEverything() {
        assertEquals(
            listOf("video-scale-x" to "1.00", "video-scale-y" to "1.00", "video-pan-x" to "0.00", "video-pan-y" to "0.00"),
            VideoZoomPolicy.mpvProperties(VideoZoom.IDENTITY),
        )
    }

    @Test
    fun surfaceTransformPansByAFractionOfTheScaledPicture() {
        // Same meaning as mpv's video-pan: 0.1 of a 1000px view scaled 1.5x moves 150px.
        val t = VideoZoomPolicy.surfaceTransform(VideoZoom(scaleX = 1.5f, scaleY = 1f, panX = 0.1f), 1000, 500)
        assertEquals(1.5f, t.scaleX)
        assertEquals(1f, t.scaleY)
        assertEquals(150f, t.translationX)
        assertEquals(0f, t.translationY)
    }

    @Test
    fun surfaceTransformMultipliesTheHostsOwnAspectScale() {
        val t = VideoZoomPolicy.surfaceTransform(VideoZoom(scaleY = 1.2f), 1920, 1080, baseScaleX = 1.33f, baseScaleY = 1.33f)
        assertEquals(1.33f, t.scaleX)
        assertEquals(1.33f * 1.2f, t.scaleY)
    }

    @Test
    fun labelShowsOneNumberWhenUniform() {
        assertEquals("125%", VideoZoomPolicy.scaleLabel(VideoZoom(1.25f, 1.25f)))
        assertEquals("120% × 100%", VideoZoomPolicy.scaleLabel(VideoZoom(1.2f, 1f)))
        assertFalse(VideoZoom(1.2f, 1f).isIdentity)
    }
}
