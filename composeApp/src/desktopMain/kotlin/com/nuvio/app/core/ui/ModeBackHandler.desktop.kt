package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/**
 * F6 on desktop: an in-screen mode leaves the way every desktop screen does, by Escape or the mouse Back button.
 * [PlatformBackHandler] already wires both (Compose's window back dispatcher plus the AWT Escape bridge that still
 * hears Escape while the native player owns focus), so the mode joins that same back stack rather than a second one.
 */
@Composable
actual fun ModeBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    PlatformBackHandler(enabled = enabled, onBack = onBack)
}
