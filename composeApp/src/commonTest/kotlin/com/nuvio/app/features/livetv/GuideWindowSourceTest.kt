package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which of the two EPG loaders a guide row shows, when they finish out of order.
 *
 * Field report 2026-08-16, real device on 1.4.32: "the rewind EPG data only loads when we click on
 * current EPG to rewind, then past programmes get populated." The history was already on disk the
 * whole time — the row was showing the panel's now-and-next, which had been fetched concurrently
 * and simply landed LAST, replacing the history that had already been drawn.
 *
 * Both reference players solve this the same way and neither lets the fallback replace anything:
 * StreamVault re-checks the guide's identity inside the atomic state update and merges the fallback
 * over the base (`baseProgramsByChannel + fallbackProgramsByChannel`), having fetched it only for
 * channels that were still missing; iptvnator treats the bulk guide as primary and `get_short_epg`
 * as a per-channel fallback for the rows the bulk guide could not answer.
 *
 * So the invariant is directional, and it is what these tests pin: history is strictly better than
 * now-and-next for a window, therefore now-and-next may never replace it — no matter which
 * coroutine happens to finish last.
 */
class GuideWindowSourceTest {

    /**
     * THE REGRESSION. Ordering is not controllable here: the history read is a local DB hit and the
     * now-and-next read is a panel round trip, so on a slow network the fallback lands second and,
     * without this rule, wins. That is the reported bug exactly.
     */
    @Test
    fun `a late now-and-next never replaces history already shown for the window`() {
        assertEquals(
            GuideWindowSource.Source.NONE,
            GuideWindowSource.forWindow(
                hasStoredHistory = false,
                historyAlreadyShown = true,
                travelling = false,
            ),
            "a now-and-next result arriving after history must be dropped, not drawn",
        )
    }

    /** The normal live-window first paint: nothing stored yet, so the panel's now-and-next draws. */
    @Test
    fun `now-and-next paints the live window before any history arrives`() {
        assertEquals(
            GuideWindowSource.Source.NOW_NEXT,
            GuideWindowSource.forWindow(
                hasStoredHistory = false,
                historyAlreadyShown = false,
                travelling = false,
            ),
        )
    }

    /** History is the better answer whenever it exists, whichever loader got there first. */
    @Test
    fun `stored history always wins`() {
        assertEquals(
            GuideWindowSource.Source.HISTORY,
            GuideWindowSource.forWindow(
                hasStoredHistory = true,
                historyAlreadyShown = false,
                travelling = false,
            ),
        )
        assertEquals(
            GuideWindowSource.Source.HISTORY,
            GuideWindowSource.forWindow(
                hasStoredHistory = true,
                historyAlreadyShown = true,
                travelling = true,
            ),
        )
    }

    /**
     * A travelled window has no now-and-next to fall back on — "now" is not in it. Drawing the
     * panel's current programme there would put a live programme under a past timestamp, which is
     * worse than the row's own "No EPG" placeholder.
     */
    @Test
    fun `a travelled window with no history shows nothing rather than now-and-next`() {
        assertEquals(
            GuideWindowSource.Source.NONE,
            GuideWindowSource.forWindow(
                hasStoredHistory = false,
                historyAlreadyShown = false,
                travelling = true,
            ),
        )
    }
}
