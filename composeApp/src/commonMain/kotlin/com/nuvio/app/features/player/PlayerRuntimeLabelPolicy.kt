package com.nuvio.app.features.player

/**
 * Whether the player draws its runtime text ("00:15 / 00:40", or "−00:25" remaining) beside the
 * action row.
 *
 * P1 (W2 device pass): a live stream has no finite timeline — what an engine reports as its
 * "duration" is the buffered window, so "00:15 / 00:40" on a live channel is noise. Live gets no
 * runtime (the controls show the LIVE badge instead); VOD and a catch-up replay — a finite
 * programme with its own transport — keep their times. Kept free of the formatter (PlayerLayout.kt
 * is UI and not compiled for tvOS).
 */
internal object PlayerRuntimeLabelPolicy {
    fun showsRuntime(isLive: Boolean): Boolean = !isLive
}
