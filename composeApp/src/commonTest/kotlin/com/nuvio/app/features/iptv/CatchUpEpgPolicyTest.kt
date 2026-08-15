package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The historical-EPG ingest rules: how far either side of now a `get_simple_data_table` row is
 * worth keeping, when a channel is worth re-asking, and where the prune cutoff falls.
 *
 * These are the memory guards, not cosmetics — an unbounded parse of a panel that ships months of
 * guide is exactly the shape of the XMLTV OOM this codebase already ate once.
 */
class CatchUpEpgPolicyTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    // --- the parse window -----------------------------------------------------------------

    /**
     * A panel that never states a window still gets a floor of 8 days, so the guide can travel
     * back through a provider whose `tv_archive_duration` is absent (three of three real panels
     * omitted `allowed_output_formats`; the duration is just as routinely missing).
     */
    @Test
    fun `an unknown window still keeps eight days of history`() {
        assertEquals(now - 8 * day, CatchUpEpgPolicy.parseWindowStartMs(now, catchUpDays = 0))
        assertEquals(now - 8 * day, CatchUpEpgPolicy.parseWindowStartMs(now, catchUpDays = -1))
    }

    /** A stated window WIDER than the floor wins — a 14-day provider must not be cut to 8. */
    @Test
    fun `a stated window wider than the floor wins`() {
        assertEquals(now - 14 * day, CatchUpEpgPolicy.parseWindowStartMs(now, catchUpDays = 14))
    }

    /** A stated window NARROWER than the floor still keeps the floor — cheap, and it lets the
     *  guide show yesterday on a 3-day provider whose rows the panel keeps serving. */
    @Test
    fun `a stated window narrower than the floor keeps the floor`() {
        assertEquals(now - 8 * day, CatchUpEpgPolicy.parseWindowStartMs(now, catchUpDays = 3))
    }

    /** Forward horizon is fixed: a day and a half is every panel's usable "what's on next". */
    @Test
    fun `the forward horizon is thirty six hours`() {
        assertEquals(now + 36 * hour, CatchUpEpgPolicy.parseWindowEndMs(now))
    }

    // --- row admission --------------------------------------------------------------------

    private fun keeps(startMs: Long, endMs: Long, catchUpDays: Int = 7) =
        CatchUpEpgPolicy.keepsRow(startMs, endMs, now, catchUpDays)

    @Test
    fun `a row inside the window is kept`() {
        assertTrue(keeps(now - 2 * day, now - 2 * day + hour))
        assertTrue(keeps(now + hour, now + 2 * hour))
    }

    /** Older than the window: the panel would not serve it and the guide never scrolls there. */
    @Test
    fun `a row older than the window is skipped`() {
        assertFalse(keeps(now - 30 * day, now - 30 * day + hour))
    }

    /** Beyond the forward horizon: a panel shipping a fortnight of schedule is the OOM risk. */
    @Test
    fun `a row past the forward horizon is skipped`() {
        assertFalse(keeps(now + 40 * hour, now + 41 * hour))
    }

    /**
     * Degenerate rows never reach the database. [XtreamCatchUp.actionFor] refuses them at decision
     * time too, but a row stored at epoch pollutes every window read that spans it.
     */
    @Test
    fun `degenerate rows are skipped at parse`() {
        assertFalse(keeps(0L, 0L))
        assertFalse(keeps(0L, now))
        assertFalse(keeps(now - hour, now - hour))
        assertFalse(keeps(now - hour, now - 2 * hour))
    }

    /** A row that STRADDLES the window edge is kept — it is partly visible in the guide. */
    @Test
    fun `a row straddling the window start is kept`() {
        assertTrue(keeps(now - 8 * day - hour, now - 8 * day + hour))
    }

    // --- the prune cutoff -----------------------------------------------------------------

    /** Prune uses exactly the parse window's start, so a refill never leaves rows a reader can
     *  see but a re-parse would have dropped. */
    @Test
    fun `the prune cutoff is the parse window start`() {
        assertEquals(
            CatchUpEpgPolicy.parseWindowStartMs(now, catchUpDays = 7),
            CatchUpEpgPolicy.pruneCutoffMs(now, catchUpDays = 7),
        )
    }

    // --- the fetch gate -------------------------------------------------------------------

    /** Never fetched: the gate is open. */
    @Test
    fun `a channel never fetched is fetched`() {
        assertTrue(CatchUpEpgPolicy.shouldFetch(fetchedAtMs = null, nowMs = now))
    }

    /** Fetched within the gate: left alone. The stamp is written even for an EMPTY result, so
     *  this is what stops a channel with no guide being re-asked on every scroll. */
    @Test
    fun `a channel fetched recently is not re-fetched`() {
        assertFalse(CatchUpEpgPolicy.shouldFetch(fetchedAtMs = now - hour, nowMs = now))
        assertFalse(CatchUpEpgPolicy.shouldFetch(fetchedAtMs = now - 5 * hour, nowMs = now))
    }

    @Test
    fun `a channel fetched before the gate is re-fetched`() {
        assertTrue(CatchUpEpgPolicy.shouldFetch(fetchedAtMs = now - 7 * hour, nowMs = now))
    }

    /**
     * A stamp in the FUTURE (the device clock moved backwards, or another device's clock wrote it)
     * must not lock the channel out of refresh forever.
     */
    @Test
    fun `a stamp in the future does not wedge the gate shut`() {
        assertTrue(CatchUpEpgPolicy.shouldFetch(fetchedAtMs = now + 30 * day, nowMs = now))
    }
}
