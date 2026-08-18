package com.nuvio.app.features.livetv

/**
 * Which guide rows the docked Live TV screen asks the panel for now/next.
 *
 * EPG is fetched per channel (`get_short_epg`, one call each), and the guide's LazyColumn holds
 * the account's WHOLE live lineup — real panels in telemetry carry 18,500-84,255 live channels.
 * So "ask for a row when it composes" is not a policy, it is an unbounded fan-out: measured on the
 * phone emulator against a mock panel (research/stalker-mock-portal, 10,000 channels), eight flings
 * produced **412 requests in 5.7 s peaking at 390 concurrent connections** to one host. Panels
 * commonly sell `max_connections=1`, and a provider edge that sees 390 parallel connections blocks
 * the IP rather than serving them — so the guide sat empty and the viewer gave up (field report,
 * 2026-08-17).
 *
 * The rule is the one TV reached first ([NuvioTV] GuideEpgPrefetchPolicy: "instead of one
 * get_short_epg per composed row, which made fast scrolling feel laggy") and the hub's tile queue
 * reached second (TileEpgQueue: "request count must track user engagement, not render size").
 * TV expresses it in D-pad terms — a window around the FOCUSED row, debounced. Touch has no focus,
 * so the honest analogue is the SETTLED VISIBLE range: rows that merely flew past under a fling
 * were never looked at and are never asked for.
 *
 * Pure so the ordering is pinned by tests: the failure mode is invisible in a screenshot (the guide
 * just fills "slowly"), and only a stated invariant keeps it fixed.
 */
internal object GuideEpgPrefetchPolicy {

    /**
     * Rows to prefetch either side of the visible range.
     *
     * Deliberately smaller than TV's 8: a phone shows ~9 guide rows to a TV's ~7, and a touch
     * scroll settles far more often than D-pad focus moves, so the window is re-asked more. Four
     * covers a half-screen nudge in either direction without the settle-storm a large radius makes.
     */
    const val RADIUS = 4

    /**
     * The rows to request for a settled visible range [firstVisible]..[lastVisible], in the order
     * they should RESOLVE: visible rows top-to-bottom (what the eye reads), then the radius rows
     * either side, nearest first.
     *
     * Callers feeding a newest-first queue must enqueue this list REVERSED, so the row that should
     * resolve soonest ends up at the front — see the wiring in [LiveTvScreen].
     *
     * Empty when the range is not a real position in a list of [size]; clipped at both ends.
     */
    fun windowFor(
        firstVisible: Int,
        lastVisible: Int,
        size: Int,
        radius: Int = RADIUS,
    ): List<Int> {
        if (size <= 0 || firstVisible > lastVisible) return emptyList()
        val first = firstVisible.coerceAtLeast(0)
        val last = lastVisible.coerceAtMost(size - 1)
        if (first > last) return emptyList()

        val out = ArrayList<Int>(last - first + 1 + radius * 2)
        for (i in first..last) out.add(i)
        // Then outward from the visible edges, nearest first, so a small nudge in either
        // direction finds its row already resolved.
        for (d in 1..radius) {
            (first - d).takeIf { it >= 0 }?.let(out::add)
            (last + d).takeIf { it < size }?.let(out::add)
        }
        return out
    }
}
