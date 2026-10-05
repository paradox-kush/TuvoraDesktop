package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.content.EpgCensusRow
import kotlin.test.Test
import kotlin.test.assertEquals

/** B10 — the playlist screen's coverage line. Same words on TV (GuideCensusTextTest, JUnit). */
class GuideCensusTextTest {

    @Test
    fun countsAgainstEligibleChannelsWithTheTierSplit() {
        val c = EpgCensusRow(lineup = 1_600, eligible = 1_530, manual = 0, byId = 812, byName = 392, fuzzy = 0, sources = 2, sourcesFailed = 1, builtAtMs = 0)
        assertEquals(
            "Playlist guide: 1,204 of 1,530 channels matched (812 by provider id · 392 by name)." +
                " 3 channels use a guide you picked." +
                " 70 more are 24/7, PPV or event channels without a guide. 1 of 2 guide sources failed to download.",
            GuideCensusText.line(c, picks = 3),
        )
    }

    /**
     * P7 (W2 device pass): "5 of 5 channels matched (3 by provider id · 2 by name · 1 picked by you)"
     * — the picked channel was also counted in its automatic tier, so the parts added up to 6 of 5.
     * The breakdown is the automatic split only (it sums to the headline); picks are their own line.
     */
    @Test
    fun aPickIsNotCountedTwiceInTheBreakdown() {
        val c = EpgCensusRow(lineup = 5, eligible = 5, manual = 0, byId = 3, byName = 2, fuzzy = 0, sources = 1, sourcesFailed = 0, builtAtMs = 0)
        assertEquals(
            "Playlist guide: 5 of 5 channels matched (3 by provider id · 2 by name). 1 channel uses a guide you picked.",
            GuideCensusText.line(c, picks = 1),
        )
    }

    @Test
    fun nothingMatched() {
        val c = EpgCensusRow(lineup = 10, eligible = 10, manual = 0, byId = 0, byName = 0, fuzzy = 0, sources = 1, sourcesFailed = 0, builtAtMs = 0)
        assertEquals("Playlist guide: 0 of 10 channels matched.", GuideCensusText.line(c, picks = 0))
    }
}
