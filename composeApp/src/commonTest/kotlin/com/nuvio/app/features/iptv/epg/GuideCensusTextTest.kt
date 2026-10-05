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
            "Playlist guide: 1,204 of 1,530 channels matched (812 by provider id · 392 by name · 3 picked by you)." +
                " 70 more are 24/7, PPV or event channels without a guide. 1 of 2 guide sources failed to download.",
            GuideCensusText.line(c, picks = 3),
        )
    }

    @Test
    fun nothingMatched() {
        val c = EpgCensusRow(lineup = 10, eligible = 10, manual = 0, byId = 0, byName = 0, fuzzy = 0, sources = 1, sourcesFailed = 0, builtAtMs = 0)
        assertEquals("Playlist guide: 0 of 10 channels matched.", GuideCensusText.line(c, picks = 0))
    }
}
