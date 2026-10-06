package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ms_resume_from_server_action
import nuvio.composeapp.generated.resources.ms_resume_from_server_message
import org.jetbrains.compose.resources.getString

/** The viewer already is within this of the server's position: nothing to offer. */
private const val RESUME_OFFER_NEAR_MS = 30_000L
private const val RESUME_OFFER_VISIBLE_MS = 12_000L

/**
 * "Resume from server at hh:mm" (D3): once per playing item, right after playback starts, ask the registered
 * source where it is. No Tuvora record: the server's position IS the start (seeked at once, like the references'
 * up-front start time). A Tuvora record that differs from a NEWER server position: Tuvora's record stays the
 * default and a toast offers the jump ("Continue at hh:mm?") and expires. Cheap no-op for every item no
 * source owns (all add-on and IPTV plays), one item fetch for a media-server item, never a poll.
 */
internal fun PlayerScreenRuntime.offerServerResumeIfNewer() {
    if (PlaybackResumeOfferRegistry.isEmpty) return
    val videoId = activeVideoId ?: return
    val providerId = activeProviderAddonId
    if (!PlaybackResumeOfferRegistry.handles(videoId, providerId)) return
    // Captured NOW, before this session's own progress writes bump the record's timestamp.
    val record = watchProgressUiState.entries.firstOrNull { it.videoId == videoId }
    val tuvoraPositionMs = activeInitialPositionMs.takeIf { it > 0L } ?: record?.lastPositionMs
    val tuvoraUpdatedAtMs = record?.lastUpdatedEpochMs
    val durationMs = playbackSnapshot.durationMs.takeIf { it > 0L }
    scope.launch {
        val offer = PlaybackResumeOfferRegistry.offerFor(videoId, providerId, tuvoraPositionMs, tuvoraUpdatedAtMs, durationMs) ?: return@launch
        if (activeVideoId != videoId) return@launch // the viewer moved on to another episode meanwhile
        if (playbackSnapshot.positionMs >= offer.positionMs - RESUME_OFFER_NEAR_MS) return@launch
        if (offer.autoStart) {
            // No progress of our own: the server's position is where this starts - no question asked.
            playerController?.seekTo(offer.positionMs)
            return@launch
        }
        NuvioToastController.show(
            message = getString(Res.string.ms_resume_from_server_message, formatPlaybackTime(offer.positionMs)),
            durationMillis = RESUME_OFFER_VISIBLE_MS,
            actionLabel = getString(Res.string.ms_resume_from_server_action),
            onAction = { playerController?.seekTo(offer.positionMs) },
        )
    }
}
