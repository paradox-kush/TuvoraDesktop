package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.PlaybackSessionState
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Feeds the neutral [PlaybackSessionReporterRegistry] (a media server keeping its own watched/resume
 * state in step) from the same player moments that feed the Trakt/Simkl scrobble pipeline - start,
 * pause/stop (flushWatchProgress), the periodic progress tick, a seek landing, and the external
 * player's result - but WITHOUT the TMDB-identity gate those scrobbles sit behind: a server item has no
 * resolvable Trakt identity and must still report. Every function is a cheap no-op while no reporter
 * owns the playing item (all IPTV and add-on plays), so behaviour is unchanged until a source reports.
 *
 * A session is "started" per (video, provider) key: progress and stop only follow a start, and a
 * source/episode switch ends the old session (the switch flushes before it swaps) so the new one starts
 * fresh when it plays.
 */
private fun PlayerScreenRuntime.sessionState(): PlaybackSessionState? {
    val videoId = activeVideoId ?: return null
    return PlaybackSessionState(
        videoId = videoId,
        parentMetaId = parentMetaId,
        providerAddonId = activeProviderAddonId,
        positionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        durationMs = playbackSnapshot.durationMs.coerceAtLeast(0L),
    )
}

private fun PlaybackSessionState.key() = "$videoId|$providerAddonId"

/** Returns true when this call started a new session (so the caller does not also report a resume). */
internal fun PlayerScreenRuntime.reportSessionStart(): Boolean {
    if (PlaybackSessionReporterRegistry.isEmpty) return false
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return false
    val state = sessionState() ?: return false
    if (reportedSessionKey == state.key()) return false
    if (PlaybackSessionReporterRegistry.handlersFor(state.videoId, state.providerAddonId).isEmpty()) return false
    reportedSessionKey = state.key()
    scope.launch { PlaybackSessionReporterRegistry.start(state) }
    return true
}

/** A periodic position, a pause (`paused = true`), a resume or a seek landing - only inside a started session. */
internal fun PlayerScreenRuntime.reportSessionProgress(paused: Boolean) {
    if (PlaybackSessionReporterRegistry.isEmpty) return
    val state = sessionState() ?: return
    if (reportedSessionKey != state.key()) return
    scope.launch { PlaybackSessionReporterRegistry.progress(state, paused) }
}

/** The session ended (stop, end, switch, teardown). Survives the player's own scope being cancelled. */
internal fun PlayerScreenRuntime.reportSessionStop() {
    if (PlaybackSessionReporterRegistry.isEmpty) return
    val state = sessionState() ?: return
    if (reportedSessionKey != state.key()) return
    reportedSessionKey = null
    scope.launch(NonCancellable) { PlaybackSessionReporterRegistry.stop(state) }
}
