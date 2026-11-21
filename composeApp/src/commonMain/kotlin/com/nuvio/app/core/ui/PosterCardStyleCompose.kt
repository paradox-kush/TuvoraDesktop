package com.nuvio.app.core.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/** The desktop playback mode for animated posters and episode thumbnails, from the user's setting. */
@Composable
internal fun rememberAnimatedArtworkAnimation(): NuvioDesktopAnimation =
    rememberPosterCardStyleUiState().animatedArtworkMode.toDesktopAnimation()

@Composable
internal fun rememberPosterCardStyleUiState(): PosterCardStyleUiState {
    PosterCardStyleRepository.ensureLoaded()
    val uiState by PosterCardStyleRepository.uiState.collectAsStateWithLifecycle()
    return uiState
}
