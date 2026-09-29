package com.nuvio.app.features.player

/** Where a swapped source should open: an absolute position, or a fraction of the duration. */
internal data class SourceSwapStart(
    val positionMs: Long,
    val progressFraction: Float?,
)

/**
 * Where a source swap (the IPTV credential refresh re-minting a dead link) should reopen the file.
 *
 * The resume target belongs to the playback request ([requestedStartMs] / [requestedProgressFraction]),
 * not to whatever the player reports: until the first frame the playback snapshot reads 0, so reading
 * it reopened a resume at 0:00 (B59-H1). Before the first frame the requested start wins; after it,
 * wherever the viewer actually is. Live always rejoins at the edge. Twin of NuvioTV's
 * PlaybackStartPositionPolicy, extended with the fraction resume the KMP player also carries.
 */
internal object PlaybackStartPositionPolicy {
    fun targetAfterSourceSwap(
        isLive: Boolean,
        firstFrameShown: Boolean,
        currentPositionMs: Long?,
        requestedStartMs: Long,
        requestedProgressFraction: Float?,
    ): SourceSwapStart {
        if (isLive) return SourceSwapStart(positionMs = 0L, progressFraction = null)
        val current = currentPositionMs?.coerceAtLeast(0L) ?: 0L
        if (firstFrameShown) return SourceSwapStart(positionMs = current, progressFraction = null)
        val position = maxOf(current, requestedStartMs.coerceAtLeast(0L))
        val fraction = requestedProgressFraction?.takeIf { position <= 0L && it > 0f }
        return SourceSwapStart(positionMs = position, progressFraction = fraction)
    }
}
