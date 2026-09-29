package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B59-H1 (GitHub #23): a resume whose IPTV link is re-minted by the credential refresh before the
 * first frame reopened at 0:00, because the refresh read the playback snapshot position (0 until
 * playback starts) and handed that to the swapped source as its start position.
 */
class PlaybackStartPositionPolicyTest {

    private fun target(
        isLive: Boolean = false,
        firstFrameShown: Boolean,
        currentPositionMs: Long?,
        requestedStartMs: Long,
        requestedProgressFraction: Float? = null,
    ) = PlaybackStartPositionPolicy.targetAfterSourceSwap(
        isLive = isLive,
        firstFrameShown = firstFrameShown,
        currentPositionMs = currentPositionMs,
        requestedStartMs = requestedStartMs,
        requestedProgressFraction = requestedProgressFraction,
    )

    @Test
    fun swapBeforeFirstFrameKeepsRequestedResume() {
        assertEquals(
            SourceSwapStart(positionMs = 822_000L, progressFraction = null),
            target(firstFrameShown = false, currentPositionMs = 0L, requestedStartMs = 822_000L),
            "snapshot still at 0 before the first frame",
        )
        assertEquals(
            SourceSwapStart(positionMs = 822_000L, progressFraction = null),
            target(firstFrameShown = false, currentPositionMs = null, requestedStartMs = 822_000L),
            "position unknown (engine not ready)",
        )
    }

    @Test
    fun swapBeforeFirstFrameKeepsFractionResume() {
        assertEquals(
            SourceSwapStart(positionMs = 0L, progressFraction = 0.4f),
            target(
                firstFrameShown = false,
                currentPositionMs = 0L,
                requestedStartMs = 0L,
                requestedProgressFraction = 0.4f,
            ),
            "a percentage resume (duration not yet known) survives the swap",
        )
    }

    @Test
    fun oncePlayingSwapResumesWhereViewerIs() {
        assertEquals(
            SourceSwapStart(positionMs = 900_000L, progressFraction = null),
            target(firstFrameShown = true, currentPositionMs = 900_000L, requestedStartMs = 822_000L),
            "watched past the resume point",
        )
        assertEquals(
            SourceSwapStart(positionMs = 10_000L, progressFraction = null),
            target(
                firstFrameShown = true,
                currentPositionMs = 10_000L,
                requestedStartMs = 822_000L,
                requestedProgressFraction = 0.4f,
            ),
            "sought back before the resume point",
        )
    }

    @Test
    fun liveAlwaysRejoinsAtTheEdge() {
        assertEquals(
            SourceSwapStart(positionMs = 0L, progressFraction = null),
            target(isLive = true, firstFrameShown = false, currentPositionMs = 5_000L, requestedStartMs = 822_000L),
            "live",
        )
    }

    @Test
    fun freshStartWithNoResumeStaysAtZero() {
        assertEquals(
            SourceSwapStart(positionMs = 0L, progressFraction = null),
            target(firstFrameShown = false, currentPositionMs = null, requestedStartMs = 0L),
            "no resume requested",
        )
    }
}
