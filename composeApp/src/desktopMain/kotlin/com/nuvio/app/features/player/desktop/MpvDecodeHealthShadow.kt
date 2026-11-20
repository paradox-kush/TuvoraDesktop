package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.analytics.DecodeHealthPolicy
import com.nuvio.app.features.player.ENGINE_LABEL_LIBMPV
import com.nuvio.app.features.player.PlayerStreamInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decode-health facts for the 500 ms snapshot (telemetry only), read the way [MpvVideoSizeShadow] reads the
 * video size: the snapshot runs on the UI thread and every `mpv_get_property` waits on mpv's core lock, so the
 * caller only ever gets the last cached [Facts]. The blocking reads run on the live worker via `schedule`,
 * at most one in flight, and the engine samples are the only clock (no timer of its own).
 *
 * Uses the generic `stringProperty` JNI call that all three native bridges (macOS, Windows, Linux) already
 * export, so no native code is involved and every OS reports the same set. What mpv cannot say is left
 * unknown: decoder warning lines (not forwarded to Kotlin on any desktop bridge) and the reorder depth.
 */
internal class MpvDecodeHealthShadow(
    /** Blocking mpv property read, run only on the worker; `""` for an unavailable property. */
    private val property: (String) -> String,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** What the last refresh saw. All fields are plain values; nothing here can carry a URL or a name. */
    data class Facts(
        val avSyncMs: Long? = null,
        val audioSelected: Boolean = false,
        val audioAdvancing: Boolean? = null,
        val muted: Boolean = false,
        val decoderDroppedFrames: Long = 0L,
        val videoFps: Float = 0f,
        val decodeMode: String? = null,
        val hdr: Boolean? = null,
        val videoCodec: String? = null,
        val videoHeight: Int? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val audioSampleRate: Int? = null,
    )

    @Volatile
    private var cached = Facts()
    private val refreshInFlight = AtomicBoolean(false)

    // Only touched on the worker (one refresh at a time).
    private var lastAudioPts: Double? = null
    private var audioPtsChangedAtMs = 0L
    private var refreshCount = 0
    private var shape = ShapeFacts()

    /** Lock-free: the last facts read, asking [schedule] for a refresh if none is pending. */
    fun current(schedule: (Runnable) -> Unit): Facts {
        if (refreshInFlight.compareAndSet(false, true)) {
            try {
                schedule(Runnable(::refresh))
            } catch (_: Exception) {
                // Worker stopped (release): keep the last facts, try again next tick.
                refreshInFlight.set(false)
            }
        }
        return cached
    }

    fun reset() {
        cached = Facts()
    }

    /** The cached facts as the engine's stream description, for telemetry (never a blocking read). */
    fun streamInfo(): PlayerStreamInfo {
        val f = cached
        return PlayerStreamInfo(
            videoCodec = f.videoCodec,
            videoHeight = f.videoHeight,
            videoFrameRate = f.videoFps.takeIf { it > 0f },
            audioCodec = f.audioCodec,
            audioChannelCount = f.audioChannels,
            audioSampleRate = f.audioSampleRate,
            playerEngine = ENGINE_LABEL_LIBMPV,
            hdr = f.hdr,
            decodeMode = f.decodeMode,
        )
    }

    private data class ShapeFacts(
        val hdr: Boolean? = null,
        val videoCodec: String? = null,
        val videoHeight: Int? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val audioSampleRate: Int? = null,
    )

    private fun refresh() {
        try {
            val now = clockMs()
            val pts = property("audio-pts").toDoubleOrNull()
            if (pts != null && pts != lastAudioPts) audioPtsChangedAtMs = now
            lastAudioPts = pts
            val audioSelected = property("aid").toIntOrNull() != null
            // The stream's shape changes only on a new file; re-read it on the first refresh and then every
            // SHAPE_EVERY refreshes (a channel zap reloads the file), not on every 500 ms tick.
            if (refreshCount++ % SHAPE_EVERY == 0) shape = readShape()
            cached = Facts(
                avSyncMs = property("avsync").toDoubleOrNull()?.let { (it * 1000.0).toLong() },
                audioSelected = audioSelected,
                audioAdvancing = if (audioSelected) pts != null && now - audioPtsChangedAtMs < AUDIO_FLAT_MS else null,
                muted = property("mute") == "yes",
                decoderDroppedFrames = property("decoder-frame-drop-count").toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                videoFps = property("estimated-vf-fps").toFloatOrNull()?.takeIf { it > 0f } ?: 0f,
                decodeMode = property("hwdec-current").let { if (it.isBlank()) null else if (it == "no") "sw" else "hw" },
                hdr = shape.hdr,
                videoCodec = shape.videoCodec,
                videoHeight = shape.videoHeight,
                audioCodec = shape.audioCodec,
                audioChannels = shape.audioChannels,
                audioSampleRate = shape.audioSampleRate,
            )
        } catch (_: Exception) {
            // Diagnostics only: keep the last facts.
        } finally {
            refreshInFlight.set(false)
        }
    }

    private fun readShape() = ShapeFacts(
        hdr = property("video-params/gamma").lowercase().takeIf { it.isNotBlank() }?.let { it == "pq" || it == "hlg" },
        videoCodec = property("video-format").trim().takeIf { it.isNotEmpty() },
        videoHeight = property("video-params/h").toIntOrNull()?.takeIf { it > 0 },
        audioCodec = property("audio-codec-name").trim().takeIf { it.isNotEmpty() },
        audioChannels = property("audio-params/channel-count").toIntOrNull()?.takeIf { it > 0 },
        audioSampleRate = property("audio-params/samplerate").toIntOrNull()?.takeIf { it > 0 },
    )

    /** The policy's view of [Facts] plus the snapshot's own VO counter (`frame-drop-count`). */
    fun signals(facts: Facts, voDroppedFrames: Long): DecodeHealthPolicy.Signals = DecodeHealthPolicy.Signals(
        droppedFrames = voDroppedFrames + facts.decoderDroppedFrames,
        audioAdvancing = facts.audioAdvancing,
        audioSelected = facts.audioSelected,
        audioMuted = facts.muted,
        avSyncMs = facts.avSyncMs,
        videoFps = facts.videoFps,
    )

    private companion object {
        /** Audio position unchanged this long counts as not advancing (same as the Android mpv feed). */
        const val AUDIO_FLAT_MS = 2_000L
        const val SHAPE_EVERY = 20
    }
}
