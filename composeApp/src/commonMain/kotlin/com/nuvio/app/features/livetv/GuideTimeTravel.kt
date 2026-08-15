package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.CatchUpEpgPolicy

/**
 * Where the guide's five-hour window sits on the timeline, and how far back it may travel.
 *
 * The guide renders ONE window at a time and moves it, rather than laying out the whole provider
 * window and scrolling within it. A week of schedule at the guide's scale is roughly 35,000 dp
 * wide, and the programme lane composes every block it contains — so the scrolling design that
 * looks obvious is also the one that puts a few hundred programme cells per row into composition
 * on a 1 GB box.
 *
 * Pure so the clamps are pinned by tests: the failure mode of getting them wrong is a guide that
 * silently travels somewhere the panel has nothing, which reads as the feature being broken.
 */
object GuideTimeTravel {

    /** How much of the timeline is rendered at once. */
    const val WINDOW_HOURS = 5

    /** Grid granularity — the window always starts on one of these boundaries. */
    const val SLOT_MINUTES = 30

    private const val SLOT_MS = SLOT_MINUTES * 60_000L
    private const val HOUR_MS = 60 * 60_000L

    /** One press (or one drag off the edge) moves the window by this much. */
    const val PAGE_MS = 2 * HOUR_MS

    /** How much past the LIVE view shows by default — the approved mockups draw recent history
     *  in the resting guide, so replay is discoverable without pressing Earlier. */
    const val LIVE_LOOKBACK_MS = HOUR_MS

    /** The live window: anchored one hour back, so recent past is visible and "now" sits inside. */
    fun anchorForNow(nowMs: Long): Long = floorToSlot(nowMs - LIVE_LOOKBACK_MS)

    /**
     * The furthest back the window may go.
     *
     * Bounded by what the ingest actually keeps ([CatchUpEpgPolicy.parseWindowStartMs]) rather
     * than by the panel's claimed archive window — travelling to a day whose rows were never
     * stored shows an empty guide, which looks like a bug rather than the edge of the archive.
     */
    fun earliestAnchorMs(nowMs: Long, catchUpDays: Int): Long =
        floorToSlot(CatchUpEpgPolicy.parseWindowStartMs(nowMs, catchUpDays))

    /** Travels back one page, stopping at the edge of the stored archive. */
    fun back(anchorMs: Long, nowMs: Long, catchUpDays: Int): Long =
        (anchorMs - PAGE_MS).coerceAtLeast(earliestAnchorMs(nowMs, catchUpDays))

    /**
     * Travels forward one page, never past the live window.
     *
     * The guide already shows five hours ahead of now; letting the anchor itself move into the
     * future would scroll past the end of every panel's schedule for no gain.
     */
    fun forward(anchorMs: Long, nowMs: Long): Long =
        (anchorMs + PAGE_MS).coerceAtMost(anchorForNow(nowMs))

    /** Whether the viewer has left the live window — drives the "back to now" affordance. */
    fun isTravelling(anchorMs: Long, nowMs: Long): Boolean = anchorMs < anchorForNow(nowMs)

    /**
     * Re-anchors an anchor as the clock advances.
     *
     * A viewer sitting on the live window should follow the clock across a half-hour boundary; one
     * who has travelled back must NOT be dragged forward under their finger. So the live window
     * tracks now and a travelled one is left exactly where it was put.
     */
    fun onClockTick(anchorMs: Long, previousNowMs: Long, nowMs: Long): Long =
        if (anchorMs >= anchorForNow(previousNowMs)) anchorForNow(nowMs) else anchorMs

    /** The window's end, given its start. */
    fun windowEndMs(anchorMs: Long): Long = anchorMs + WINDOW_HOURS * HOUR_MS

    private fun floorToSlot(ms: Long) = ms.floorDiv(SLOT_MS) * SLOT_MS
}
