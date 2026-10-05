package com.nuvio.app.features.player

/**
 * The runtime text beside the player's action row ("00:15 / 00:40", or "−00:25" remaining).
 *
 * P1 (W2 device pass): a live stream has no finite timeline — what an engine reports as its
 * "duration" is the buffered window, so "00:15 / 00:40" on a live channel is noise. Live gets no
 * runtime (the controls show the LIVE badge instead); VOD and a catch-up replay — a finite
 * programme with its own transport — keep their times.
 */
internal object PlayerRuntimeLabelPolicy {
    fun label(isLive: Boolean, positionMs: Long, durationMs: Long, showRemainingTime: Boolean): String? =
        if (isLive) null else formatPlaybackRuntime(positionMs, durationMs, showRemainingTime)
}
