package com.nuvio.app.features.iptv

/**
 * How much of a panel's history is worth parsing, storing and re-asking for.
 *
 * `get_simple_data_table` returns a channel's WHOLE table — a 24-hour channel on a provider that
 * keeps a fortnight of guide is a lot more than the guide can ever show, and this codebase has
 * already OOMed once on an EPG feed it read whole. So the bounds are applied at parse, before a
 * row can become an object, and the same bound is the prune cutoff so a stored row is never one a
 * re-parse would have refused.
 *
 * Pure so the numbers can be argued with in a test rather than discovered on a 1 GB box.
 */
object CatchUpEpgPolicy {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * The floor on how far back to keep rows, whatever the panel says its window is.
     *
     * `tv_archive_duration` is absent or zero on plenty of providers that do serve catch-up (the
     * same panels omit `allowed_output_formats` — three of three measured), so a window derived
     * purely from it would give those users no history at all. Eight days also covers the common
     * 7-day provider plus the day either side that a timezone-shifted panel puts rows in.
     */
    const val MIN_HISTORY_DAYS = 8

    /** How far forward to keep rows. A day and a half is every panel's usable "what's on next". */
    const val FUTURE_HORIZON_MS = 36L * 60 * 60 * 1000

    /**
     * How long a channel's fetched guide is trusted before re-asking. Deliberately generous: the
     * table barely changes within a day, and the stamp is written even for an EMPTY answer, so
     * this is also what stops a channel the provider has no guide for being re-asked forever.
     */
    const val FETCH_GATE_MS = 6L * 60 * 60 * 1000

    /** Oldest row worth keeping — the panel's stated window, never less than [MIN_HISTORY_DAYS]. */
    fun parseWindowStartMs(nowMs: Long, catchUpDays: Int): Long =
        nowMs - maxOf(catchUpDays, MIN_HISTORY_DAYS) * DAY_MS

    /** Newest row worth keeping. */
    fun parseWindowEndMs(nowMs: Long): Long = nowMs + FUTURE_HORIZON_MS

    /**
     * Whether one parsed row survives to the database.
     *
     * Degenerate rows are refused here as well as in [XtreamCatchUp.actionFor]: a zero-length
     * programme stored at epoch would be returned by every window read that spans it, and the only
     * thing it can produce is a guaranteed-dead replay URL.
     */
    fun keepsRow(startMs: Long, endMs: Long, nowMs: Long, catchUpDays: Int): Boolean {
        if (startMs <= 0 || endMs <= startMs) return false
        // Overlap, not containment: a programme straddling either edge is partly visible.
        return endMs > parseWindowStartMs(nowMs, catchUpDays) && startMs < parseWindowEndMs(nowMs)
    }

    /** Rows ending before this are dropped on write — exactly the parse window's own start. */
    fun pruneCutoffMs(nowMs: Long, catchUpDays: Int): Long = parseWindowStartMs(nowMs, catchUpDays)

    /**
     * Whether this channel's guide should be fetched now.
     *
     * A stamp in the FUTURE also opens the gate: a device whose clock moved backwards (or a stamp
     * written by another device) would otherwise lock the channel out of refresh permanently.
     */
    fun shouldFetch(fetchedAtMs: Long?, nowMs: Long): Boolean {
        if (fetchedAtMs == null) return true
        val age = nowMs - fetchedAtMs
        return age >= FETCH_GATE_MS || age < 0
    }
}
