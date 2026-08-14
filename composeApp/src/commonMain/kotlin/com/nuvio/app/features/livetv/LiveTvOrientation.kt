package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Orientation policy for the Live TV screen.
 *
 * [Sensor] lets the device drive rotation (so physically rotating the phone toggles fullscreen).
 * [ForceLandscape] is the sticky, button-driven fullscreen override and remains active until the
 * viewer explicitly exits. [ForcePortrait] is the exit transition: it pins portrait until the
 * device physically catches up, then the screen hands control back to [Sensor] (see
 * [rememberPhysicalLandscape]). That coexistence makes rotate-to-fullscreen work without allowing
 * incidental device movement to cancel an explicitly entered fullscreen session.
 */
enum class LiveOrientationMode { Sensor, ForceLandscape, ForcePortrait }

/** Applies the requested orientation policy while this composable is in composition. */
@Composable
expect fun ApplyLiveOrientation(mode: LiveOrientationMode)

/**
 * Whether Live TV fullscreen is a consequence of the window's aspect ratio.
 *
 * On phones and tablets the window tracks the device, so rotating to landscape IS going fullscreen
 * and [ApplyLiveOrientation] can drive the layout. A desktop window is landscape at every size, so
 * aspect ratio can't mean fullscreen there — the guide would be unreachable and the exit-fullscreen
 * button would be inert, because forcing portrait is a no-op on desktop. Desktop therefore toggles
 * fullscreen explicitly instead.
 */
expect val LiveTvFullscreenFollowsWindowAspect: Boolean

/**
 * Reactive PHYSICAL device orientation, independent of any app-level orientation lock.
 * true = device physically landscape, false = portrait, null = unknown/flat/face-up.
 */
@Composable
expect fun rememberPhysicalLandscape(): State<Boolean?>
