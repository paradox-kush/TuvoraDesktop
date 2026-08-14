package com.nuvio.app.features.player

import com.nuvio.app.core.analytics.LivePlaybackFreezePolicy
import com.nuvio.app.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.app.core.analytics.LivePlaybackReconnector
import com.nuvio.app.features.trakt.TraktPlatformClock

/** The docked Live TV screen. */
const val LIVE_FREEZE_SURFACE_DOCKED = "livetv_docked"

/** A live channel opened through the full player rather than the docked screen. */
const val LIVE_FREEZE_SURFACE_PLAYER = "player"

/**
 * Adapts [PlayerPlaybackSnapshot] — the one shape every mobile engine already reports — into
 * freeze detection, reconnect, and reporting.
 *
 * Nothing in the player layer re-prepares a live source, so a clean upstream close or a dead
 * socket leaves a frozen picture with no error. [reconnect] is the automated form of the
 * channel-change-and-back viewers do by hand; it should re-prepare the current source.
 *
 * Arming is deferred until the channel has actually produced a frame: a slow channel open is a
 * different problem, and reporting or reconnecting it would bury the real signal.
 */
fun LivePlaybackFreezeReporter.onLiveSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    engine: () -> String?,
    streamUrl: String?,
    contentId: String?,
    surface: String,
    reconnector: LivePlaybackReconnector,
    reconnect: () -> Unit,
) {
    val nowMs = TraktPlatformClock.nowEpochMs()
    val started = snapshot.positionMs > 0L || snapshot.isPlaying

    if (!isArmed && started) {
        onLivePlaybackStarted(
            profile = LivePlaybackFreezeReporter.Profile(
                engine = engine()?.lowercase() ?: "unknown",
                streamContainer = LivePlaybackFreezeReporter.streamContainerOf(streamUrl),
                iptvKind = LivePlaybackFreezeReporter.iptvKindOf(contentId),
                surface = surface,
            ),
            nowMs = nowMs,
            positionMs = snapshot.positionMs,
            videoProgressTicks = snapshot.videoProgressTicks,
        )
        reconnector.reset()
    }

    val decision = onSample(
        nowMs = nowMs,
        positionMs = snapshot.positionMs,
        bufferedPositionMs = snapshot.bufferedPositionMs,
        state = snapshot.toFreezeState(),
        // The snapshot has no "user paused" flag: a viewer pause reports isPlaying=false with
        // isLoading=false, which maps to IDLE and is never treated as wanting to play.
        wantsToPlay = snapshot.isPlaying || snapshot.isLoading || snapshot.isEnded,
        videoProgressTicks = snapshot.videoProgressTicks,
        hasVideoTrack = snapshot.hasVideoTrack,
    )

    when (decision) {
        is LivePlaybackFreezePolicy.Decision.Start,
        is LivePlaybackFreezePolicy.Decision.Continue,
        -> reconnector.onFrozen(nowMs, reconnect)

        else -> Unit
    }
}

/** Flushes an unresolved freeze — leaving the screen while frozen is the reported workaround. */
fun LivePlaybackFreezeReporter.onLiveSnapshotStopped(snapshot: PlayerPlaybackSnapshot) {
    onLivePlaybackStopped(
        nowMs = TraktPlatformClock.nowEpochMs(),
        positionMs = snapshot.positionMs,
        bufferedPositionMs = snapshot.bufferedPositionMs,
    )
}

private fun PlayerPlaybackSnapshot.toFreezeState(): LivePlaybackFreezePolicy.PlaybackState = when {
    // A live channel has no end, so ENDED is always the fault this exists to catch.
    isEnded -> LivePlaybackFreezePolicy.PlaybackState.ENDED
    isLoading -> LivePlaybackFreezePolicy.PlaybackState.BUFFERING
    isPlaying -> LivePlaybackFreezePolicy.PlaybackState.READY
    else -> LivePlaybackFreezePolicy.PlaybackState.IDLE
}
