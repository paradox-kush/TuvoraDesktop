package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.analytics.DecodeHealthPolicy
import com.nuvio.app.core.analytics.DecodeHealthReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decode-health feed must come from a shadow refreshed on the worker, never an mpv read on the caller. */
class MpvDecodeHealthShadowTest {
    private val props = mutableMapOf(
        "audio-pts" to "10.0", "aid" to "1", "avsync" to "0.02", "mute" to "no",
        "decoder-frame-drop-count" to "3", "estimated-vf-fps" to "25.0", "hwdec-current" to "videotoolbox",
        "video-params/gamma" to "bt.1886", "video-format" to "hevc", "video-params/h" to "1080",
        "audio-codec-name" to "eac3", "audio-params/channel-count" to "6", "audio-params/samplerate" to "48000",
    )
    private var reads = 0
    private var now = 0L
    private val queued = mutableListOf<Runnable>()
    private val shadow = MpvDecodeHealthShadow(property = { reads++; props[it].orEmpty() }, clockMs = { now })

    private fun tick(): MpvDecodeHealthShadow.Facts {
        val facts = shadow.current(queued::add)
        queued.removeAll { it.run(); true }
        return facts
    }

    @Test
    fun `the caller never reads mpv, the worker does`() {
        assertEquals(MpvDecodeHealthShadow.Facts(), shadow.current(queued::add), "nothing cached yet")
        assertEquals(0, reads, "no blocking read on the caller")
        assertEquals(1, queued.size)
        assertEquals(1, shadow.current(queued::add).let { queued.size }, "at most one refresh in flight")
    }

    @Test
    fun `facts land for the next tick`() {
        tick()
        val f = tick()
        assertEquals(20L, f.avSyncMs)
        assertEquals(3L, f.decoderDroppedFrames)
        assertEquals("hw", f.decodeMode)
        assertEquals(false, f.hdr)
        assertEquals("hevc", f.videoCodec)
        assertEquals(1080, f.videoHeight)
        assertEquals("eac3", f.audioCodec)
        assertEquals(6, f.audioChannels)
        assertEquals(48000, f.audioSampleRate)
        assertTrue(f.audioSelected)
        assertFalse(f.muted)
    }

    @Test
    fun `audio advancing follows audio-pts and goes false when flat`() {
        tick(); now += 500; tick()
        assertEquals(true, tick().audioAdvancing)
        now += 2_500
        tick() // the refresh lands for the next read
        assertEquals(false, tick().audioAdvancing, "pts did not change for over 2 s")
        props["audio-pts"] = "13.0"
        tick()
        assertEquals(true, tick().audioAdvancing)
    }

    @Test
    fun `no audio track is unknown not stalled`() {
        props["aid"] = "no"; props["audio-pts"] = ""
        tick()
        val f = tick()
        assertFalse(f.audioSelected)
        assertNull(f.audioAdvancing)
    }

    @Test
    fun `software decode, hdr and mute are read`() {
        props["hwdec-current"] = "no"; props["video-params/gamma"] = "pq"; props["mute"] = "yes"
        tick()
        val f = tick()
        assertEquals("sw", f.decodeMode)
        assertEquals(true, f.hdr)
        assertTrue(f.muted)
        props["hwdec-current"] = ""
        tick()
        assertNull(tick().decodeMode)
    }

    @Test
    fun `shape is re-read only every twentieth refresh`() {
        tick()
        val afterFirst = reads
        tick()
        val perTick = reads - afterFirst
        assertTrue(perTick < 12, "later ticks skip the shape reads, got $perTick")
    }

    @Test
    fun `signals add the vo and decoder drops and carry no b-frame claim`() {
        tick()
        val f = tick()
        val s = shadow.signals(f, voDroppedFrames = 7)
        assertEquals(10L, s.droppedFrames)
        assertEquals(25f, s.videoFps)
        assertNull(shadow.streamInfo().videoReorderDepth)
        assertEquals("libmpv", shadow.streamInfo().playerEngine)
    }

    @Test
    fun `a worker that cannot take the refresh keeps the last facts`() {
        tick()
        val f = shadow.current { throw java.util.concurrent.RejectedExecutionException() }
        assertEquals(f, shadow.current { throw java.util.concurrent.RejectedExecutionException() })
    }

    @Test
    fun `a flat audio clock reports once through the reporter with the desktop platform`() {
        val sent = mutableListOf<Map<String, Any>>()
        val reporter = DecodeHealthReporter(platform = "macos", report = { _, p -> sent += p })
        tick(); tick()
        var t = 100_000L
        repeat(40) {
            t += 500; now = t
            val facts = tick()
            reporter.onSample(
                tune = "ch1",
                sample = DecodeHealthPolicy.Sample(
                    nowMs = t, playing = true, hasVideoTrack = true, surfaceAudible = true,
                    videoProgressTicks = it.toLong(), positionMs = 0L, signals = shadow.signals(facts, 0L),
                ),
                surface = "player",
                shape = { null },
            )
        }
        assertEquals(1, sent.size, "once per issue per tune")
        assertEquals("macos", sent.single()["platform"])
        assertEquals("audio_not_playing", sent.single()["issue"])
        assertFalse(sent.single().keys.any { it in setOf("url", "token", "host", "code", "state") })
    }
}
