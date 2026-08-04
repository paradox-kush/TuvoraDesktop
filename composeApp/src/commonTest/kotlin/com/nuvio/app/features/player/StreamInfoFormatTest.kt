package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * These expectations are copied from what NuvioTV's StreamInfoOverlay renders. If a case
 * here changes, the TV formatter has to change with it or the two apps will describe the
 * same stream differently.
 */
class StreamInfoFormatTest {

    @Test
    fun `resolution matches the TV panel, including the quality shorthand`() {
        // Verified against a live capture of the TV panel playing a 1920x804 scope source.
        assertEquals("1920 × 804 (1080p)", StreamInfoFormat.resolution(1920, 804))
        assertEquals("1920 × 1080 (1080p)", StreamInfoFormat.resolution(1920, 1080))
        assertEquals("3840 × 2160 (4K)", StreamInfoFormat.resolution(3840, 2160))
        assertEquals("1280 × 720 (720p)", StreamInfoFormat.resolution(1280, 720))
        assertEquals("720 × 480 (480p)", StreamInfoFormat.resolution(720, 480))
    }

    @Test
    fun `letterboxed widescreen is classified on width, not cropped height`() {
        // The failure this guards: keying off height alone demotes scope films to 720p.
        assertEquals("1920 × 800 (1080p)", StreamInfoFormat.resolution(1920, 800))
    }

    @Test
    fun `resolution is absent unless both dimensions are known`() {
        assertNull(StreamInfoFormat.resolution(null, 1080))
        assertNull(StreamInfoFormat.resolution(1920, null))
        assertNull(StreamInfoFormat.resolution(0, 0))
    }

    @Test
    fun `bitrate keeps one decimal for megabits, like the TV panel`() {
        // Verified against the live capture: 2229472 bps rendered as "2.2 Mbps".
        assertEquals(StreamInfoFormat.Bitrate("2.2", isMegabits = true), StreamInfoFormat.bitrate(2_229_472))
        assertEquals(StreamInfoFormat.Bitrate("6.0", isMegabits = true), StreamInfoFormat.bitrate(6_000_000))
        assertEquals(StreamInfoFormat.Bitrate("128", isMegabits = false), StreamInfoFormat.bitrate(128_000))
    }

    @Test
    fun `bitrate is absent when the stream declares nothing`() {
        assertNull(StreamInfoFormat.bitrate(null))
        assertNull(StreamInfoFormat.bitrate(0))
        assertNull(StreamInfoFormat.bitrate(-1))
    }

    @Test
    fun `frame rate drops decimals only when the rate is whole`() {
        assertEquals("50", StreamInfoFormat.frameRate(50f))
        assertEquals("24", StreamInfoFormat.frameRate(24f))
        assertEquals("23.976", StreamInfoFormat.frameRate(23.976f))
        assertEquals("29.97", StreamInfoFormat.frameRate(29.97f))
        assertNull(StreamInfoFormat.frameRate(0f))
        assertNull(StreamInfoFormat.frameRate(null))
    }

    @Test
    fun `sample rate reports whole kilohertz`() {
        assertEquals("48", StreamInfoFormat.sampleRate(48_000))
        assertEquals("44", StreamInfoFormat.sampleRate(44_100))
        assertNull(StreamInfoFormat.sampleRate(0))
    }

    @Test
    fun `channel layout names only the counts that have conventional labels`() {
        assertEquals("5.1", StreamInfoFormat.channelLayout(6))
        assertEquals("7.1", StreamInfoFormat.channelLayout(8))
        // Mono and stereo are words; the panel localises those rather than hardcoding them.
        assertNull(StreamInfoFormat.channelLayout(1))
        assertNull(StreamInfoFormat.channelLayout(2))
        assertNull(StreamInfoFormat.channelLayout(5))
    }

    @Test
    fun `codec names agree with the labels ExoPlayer produces`() {
        assertEquals("HEVC", StreamCodecNames.display("hevc"))
        assertEquals("H.264", StreamCodecNames.display("h264"))
        assertEquals("E-AC-3", StreamCodecNames.display("eac3"))
        assertEquals("TrueHD", StreamCodecNames.display("truehd"))
        // Idempotent: an already-formatted label survives a second pass unchanged.
        listOf("HEVC", "H.264", "AV1", "E-AC-3", "TrueHD", "AAC", "DTS-HD").forEach {
            assertEquals(it, StreamCodecNames.display(it))
        }
        assertEquals("PRORES", StreamCodecNames.display("prores"))
        assertNull(StreamCodecNames.display(null))
        assertNull(StreamCodecNames.display("  "))
    }
}
