package com.nuvio.app.core.telemetry

import com.nuvio.app.core.telemetry.api.PlaybackTelemetryKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTelemetryKeysTest {
    @Test
    fun `count keys use the shared strings`() {
        assertEquals("ttff:live:libmpv:lt2000", PlaybackTelemetryKeys.ttff("live", "libmpv", 1_840L))
        assertEquals("ttff:multiview_tile:media3:ge8000", PlaybackTelemetryKeys.ttff("multiview_tile", "exoplayer", 9_000L))
        assertEquals("fallback:media3:libmpv:decoder_failed", PlaybackTelemetryKeys.fallback("exo", "mpv", "decoder_failed"))
        assertEquals("stall:live:recovered", PlaybackTelemetryKeys.stall("live", recovered = true))
        assertEquals("stall:vod:gave_up", PlaybackTelemetryKeys.stall("vod", recovered = false))
        assertEquals("stall_ms:live:lt15000", PlaybackTelemetryKeys.stallMs("live", 7_500L))
        assertEquals("teardown:libmpv:clean", PlaybackTelemetryKeys.teardown("libmpv", 1_500L, failed = false))
        assertEquals("teardown:libmpv:slow", PlaybackTelemetryKeys.teardown("libmpv", 1_501L, failed = false))
        assertEquals("teardown:libmpv:failed", PlaybackTelemetryKeys.teardown("libmpv", 10L, failed = true))
        assertEquals("multiview:tile_failed", PlaybackTelemetryKeys.multiview("tile_failed"))
        assertEquals("multiview_tiles_max:4", PlaybackTelemetryKeys.multiviewTilesMax(4))
    }

    @Test
    fun `unknown values are normalised or refused never passed through`() {
        assertEquals("other", PlaybackTelemetryKeys.engine("some-new-engine"))
        assertEquals("other", PlaybackTelemetryKeys.engine(null))
        assertEquals("media3", PlaybackTelemetryKeys.engine("ExoPlayer"))
        assertEquals("libmpv", PlaybackTelemetryKeys.engine("mpv"))
        assertEquals("other", PlaybackTelemetryKeys.fallbackTrigger("http://provider.example/stream"))
        assertNull(PlaybackTelemetryKeys.ttff("catchup", "libmpv", 10L), "an unknown kind counts nothing")
        assertNull(PlaybackTelemetryKeys.stall("http://x", true))
        assertNull(PlaybackTelemetryKeys.multiview("opened; drop table"))
        assertNull(PlaybackTelemetryKeys.multiviewTilesMax(0))
        assertNull(PlaybackTelemetryKeys.multiviewTilesMax(10))
    }

    @Test
    fun `isValid accepts only keys the builders make`() {
        assertTrue(PlaybackTelemetryKeys.isValid("ttff:live:libmpv:lt250"))
        assertTrue(PlaybackTelemetryKeys.isValid("multiview_tiles_max:9"))
        assertFalse(PlaybackTelemetryKeys.isValid("ttff:live:libmpv:lt999"), "bucket outside the list")
        assertFalse(PlaybackTelemetryKeys.isValid("ttff:live:libmpv"), "missing dimension")
        assertFalse(PlaybackTelemetryKeys.isValid("ttff:live:libmpv:lt250:extra"))
        assertFalse(PlaybackTelemetryKeys.isValid("ttff:channel_name:libmpv:lt250"))
        assertFalse(PlaybackTelemetryKeys.isValid("stall:live:frozen"))
        assertFalse(PlaybackTelemetryKeys.isValid("teardown:libmpv:ok"))
        assertFalse(PlaybackTelemetryKeys.isValid("multiview_tiles_max:0"))
        assertFalse(PlaybackTelemetryKeys.isValid("playback_started_exo_live"), "legacy counts do not go through this port")
        assertFalse(PlaybackTelemetryKeys.isValid(""))
    }

    @Test
    fun `previous run facts are closed values and absent when unknown`() {
        assertEquals(emptyMap(), PlaybackTelemetryKeys.previousRun(null, "live", "libmpv"), "an old marker says nothing")
        assertEquals(mapOf("prev_playing" to false), PlaybackTelemetryKeys.previousRun(false, "live", "libmpv"))
        assertEquals(
            mapOf("prev_playing" to true, "prev_kind" to "live", "prev_engine" to "libmpv"),
            PlaybackTelemetryKeys.previousRun(true, "live", "mpv"),
        )
        assertEquals(
            mapOf("prev_playing" to true, "prev_engine" to "other"),
            PlaybackTelemetryKeys.previousRun(true, "http://x", "whatever"),
            "an unclean kind is left out and an unknown engine is other",
        )
    }

    @Test
    fun `diagnostic classes`() {
        assertEquals("m3u8", PlaybackTelemetryKeys.extClass("https://h.example/a/b.M3U8?token=1"))
        assertEquals("ts", PlaybackTelemetryKeys.extClass("http://h/live/1.ts"))
        assertEquals("none", PlaybackTelemetryKeys.extClass(null))
        assertEquals("other", PlaybackTelemetryKeys.extClass("http://h/stream"))
        assertEquals("none", PlaybackTelemetryKeys.httpClass(null))
        assertEquals("2xx", PlaybackTelemetryKeys.httpClass(206))
        assertEquals("3xx", PlaybackTelemetryKeys.httpClass(302))
        assertEquals("401_403", PlaybackTelemetryKeys.httpClass(401))
        assertEquals("401_403", PlaybackTelemetryKeys.httpClass(403))
        assertEquals("404", PlaybackTelemetryKeys.httpClass(404))
        assertEquals("456", PlaybackTelemetryKeys.httpClass(456))
        assertEquals("4xx_other", PlaybackTelemetryKeys.httpClass(429))
        assertEquals("5xx", PlaybackTelemetryKeys.httpClass(503))
        assertEquals("hls", PlaybackTelemetryKeys.contentTypeClass("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("mp2t", PlaybackTelemetryKeys.contentTypeClass("video/MP2T"))
        assertEquals("octet", PlaybackTelemetryKeys.contentTypeClass("application/octet-stream"))
        assertEquals("html", PlaybackTelemetryKeys.contentTypeClass("text/html"))
        assertEquals("text", PlaybackTelemetryKeys.contentTypeClass("text/plain"))
        assertEquals("video", PlaybackTelemetryKeys.contentTypeClass("video/mp4"))
        assertEquals("other", PlaybackTelemetryKeys.contentTypeClass(null))
    }
}
