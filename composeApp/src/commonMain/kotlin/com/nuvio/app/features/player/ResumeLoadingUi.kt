package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_loading_resuming
import nuvio.composeapp.generated.resources.player_loading_resuming_slow
import nuvio.composeapp.generated.resources.player_start_from_beginning
import org.jetbrains.compose.resources.stringResource

/** What the opening overlay shows while a resume loads: a message and, once slow, a way out. */
internal data class ResumeLoadingUi(
    val message: String?,
    val startOverLabel: String?,
    val onStartOver: (() -> Unit)?,
)

/**
 * The loading screen says it is resuming (and where), and after
 * [ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS] without a first frame offers "Start from beginning".
 */
@Composable
internal fun PlayerScreenRuntime.resumeLoadingUi(): ResumeLoadingUi {
    val isResume = ResumeLoadPolicy.isResumeLoad(
        initialPositionMs = activeInitialPositionMs,
        isLive = activeStreamType.equals("live", ignoreCase = true),
    )
    // Keyed on the first real frame, not on the file opening: a slow provider stalls after the open,
    // on the jump to the saved position, and the viewer must still get a way out then.
    LaunchedEffect(activePlaybackKey, startOverGeneration, isResume, resumePlaybackStarted) {
        startOverOffered = false
        if (!isResume || resumePlaybackStarted) return@LaunchedEffect
        delay(ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS)
        startOverOffered = ResumeLoadPolicy.offerStartOver(
            isResumeLoad = isResume,
            firstFrameShown = resumePlaybackStarted,
            loadingForMs = ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS,
        )
    }
    if (!isResume || resumePlaybackStarted) return ResumeLoadingUi(null, null, null)
    val at = formatPlaybackTime(activeInitialPositionMs)
    return if (startOverOffered) {
        ResumeLoadingUi(
            message = stringResource(Res.string.player_loading_resuming_slow, at),
            startOverLabel = stringResource(Res.string.player_start_from_beginning),
            onStartOver = { startOverFromBeginning() },
        )
    } else {
        ResumeLoadingUi(
            message = stringResource(Res.string.player_loading_resuming, at),
            startOverLabel = null,
            onStartOver = null,
        )
    }
}
