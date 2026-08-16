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

/**
 * What a guide row should show for one (channel, window), given what has arrived so far.
 *
 * Two loaders write the same row and they finish out of order: the panel's now-and-next is a fast
 * first paint, the channel's stored history arrives later and is strictly better. Field-reported
 * 2026-08-16 ("rewind EPG only loads once you tap a programme"): the now-and-next fetch landed
 * AFTER the history and overwrote it, so the row sat on now-and-next with an empty past until some
 * later interaction happened to reload it — the history was on disk the whole time.
 *
 * So the rule is an ordering one, not a timing one: **once history has been shown for a window,
 * now-and-next may never replace it.** Pure, because the bug was invisible in a screenshot and only
 * a stated invariant makes it stay fixed.
 */
object GuideWindowSource {

    enum class Source {
        /** Stored history for this window — always wins. */
        HISTORY,

        /** The panel's now-and-next: the live window's first paint, and all a non-Xtream row has. */
        NOW_NEXT,

        /** Draw the row's "No EPG" placeholder rather than something from the wrong window. */
        NONE,
    }

    /**
     * [historyAlreadyShown] is per (channel, window): a late now-and-next result for a window whose
     * history has already landed is stale by definition, whatever order the two coroutines finish in.
     */
    fun forWindow(
        hasStoredHistory: Boolean,
        historyAlreadyShown: Boolean,
        travelling: Boolean,
    ): Source = when {
        hasStoredHistory -> Source.HISTORY
        historyAlreadyShown -> Source.NONE
        travelling -> Source.NONE      // a past window has no now-and-next to fall back on
        else -> Source.NOW_NEXT
    }
}
