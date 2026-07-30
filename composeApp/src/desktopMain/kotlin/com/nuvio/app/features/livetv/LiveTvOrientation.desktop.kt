package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

/** Desktop windows have no device orientation — the orientation policy is a no-op. */
@Composable
actual fun ApplyLiveOrientation(mode: LiveOrientationMode) = Unit

/** No physical orientation on desktop; always unknown so the UI never auto-rotates. */
@Composable
actual fun rememberPhysicalLandscape(): State<Boolean?> =
    remember { mutableStateOf(null) }
