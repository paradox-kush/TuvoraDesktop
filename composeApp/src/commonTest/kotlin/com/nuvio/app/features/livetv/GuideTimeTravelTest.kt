package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.CatchUpEpgPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the guide window may travel, and where it must refuse to. */
class GuideTimeTravelTest {

    private val hour = 60 * 60_000L
    private val day = 24 * hour

    /** A round slot boundary plus 11 minutes — nothing here may depend on now being aligned. */
    private val now = 1_710_000_000_000L + 11 * 60_000L

    @Test
    fun `the live anchor is the slot boundary at or before now`() {
        val anchor = GuideTimeTravel.anchorForNow(now)
        assertTrue(anchor <= now, "the anchor must not be in the future")
        assertTrue(now - anchor < GuideTimeTravel.SLOT_MINUTES * 60_000L, "the anchor must be the NEAREST boundary")
        assertEquals(0L, anchor % (GuideTimeTravel.SLOT_MINUTES * 60_000L), "the anchor must sit on a slot boundary")
    }

    @Test
    fun `travelling back moves one page`() {
        val live = GuideTimeTravel.anchorForNow(now)
        assertEquals(live - GuideTimeTravel.PAGE_MS, GuideTimeTravel.back(live, now, catchUpDays = 7))
    }

    /**
     * The clamp. Travelling past what the ingest kept shows an empty guide, which reads as the
     * feature being broken rather than as the edge of the provider's archive.
     */
    @Test
    fun `travelling back stops at the edge of the stored archive`() {
        val earliest = GuideTimeTravel.earliestAnchorMs(now, catchUpDays = 7)
        var anchor = GuideTimeTravel.anchorForNow(now)
        repeat(400) { anchor = GuideTimeTravel.back(anchor, now, catchUpDays = 7) }
        assertEquals(earliest, anchor, "400 pages back should land exactly on the floor")
    }

    /** The floor follows the ingest window, so a wider provider really does travel further. */
    @Test
    fun `a wider provider window travels further back`() {
        val narrow = GuideTimeTravel.earliestAnchorMs(now, catchUpDays = 7)
        val wide = GuideTimeTravel.earliestAnchorMs(now, catchUpDays = 14)
        assertTrue(wide < narrow, "a 14-day provider must reach further back than a 7-day one")
        assertTrue(
            narrow <= CatchUpEpgPolicy.parseWindowStartMs(now, 7) + GuideTimeTravel.SLOT_MINUTES * 60_000L,
            "the floor must track the parse window it is derived from",
        )
    }

    /** An unknown window still travels — the eight-day floor is the whole point of having one. */
    @Test
    fun `an unknown provider window still travels back`() {
        val live = GuideTimeTravel.anchorForNow(now)
        val earliest = GuideTimeTravel.earliestAnchorMs(now, catchUpDays = 0)
        assertTrue(earliest < live - 7 * day, "an unstated window must still reach about eight days")
    }

    @Test
    fun `travelling forward never passes the live window`() {
        val live = GuideTimeTravel.anchorForNow(now)
        assertEquals(live, GuideTimeTravel.forward(live, now))
        assertEquals(live, GuideTimeTravel.forward(live - GuideTimeTravel.PAGE_MS / 2, now))
        assertEquals(live - GuideTimeTravel.PAGE_MS, GuideTimeTravel.forward(live - 2 * GuideTimeTravel.PAGE_MS, now))
    }

    @Test
    fun `travelling is only true away from the live window`() {
        val live = GuideTimeTravel.anchorForNow(now)
        assertFalse(GuideTimeTravel.isTravelling(live, now))
        assertTrue(GuideTimeTravel.isTravelling(live - GuideTimeTravel.PAGE_MS, now))
    }

    /** A viewer sitting on the live window follows the clock over a half-hour boundary. */
    @Test
    fun `the live window follows the clock`() {
        val earlier = now
        val later = now + hour
        val live = GuideTimeTravel.anchorForNow(earlier)
        assertEquals(GuideTimeTravel.anchorForNow(later), GuideTimeTravel.onClockTick(live, earlier, later))
    }

    /** A viewer who has travelled back must not be dragged forward under their finger. */
    @Test
    fun `a travelled window is left where it was put`() {
        val earlier = now
        val later = now + hour
        val travelled = GuideTimeTravel.anchorForNow(earlier) - 4 * GuideTimeTravel.PAGE_MS
        assertEquals(travelled, GuideTimeTravel.onClockTick(travelled, earlier, later))
    }

    @Test
    fun `the window spans the configured hours`() {
        val anchor = GuideTimeTravel.anchorForNow(now)
        assertEquals(anchor + GuideTimeTravel.WINDOW_HOURS * hour, GuideTimeTravel.windowEndMs(anchor))
    }
}
