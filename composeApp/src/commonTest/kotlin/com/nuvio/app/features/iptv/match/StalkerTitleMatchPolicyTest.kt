package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KMP twin of NuvioTV's StalkerTitleMatchPolicyTest (kotlin.test order: value, message).
 *
 * B122 (2026-10-04): Stalker playlists were never offered as a source for add-on titles, while
 * Xtream was. Xtream matches through the index, which keys every catalog name with
 * [TitleNormalizer.keysOf] (provider tags, years, language suffixes stripped). The Stalker lane
 * compared the portal's raw name with plain normKey EQUALITY, so the everyday portal naming —
 * "EN - The Matrix (1999)", "|EN| The Matrix", "The Matrix 4K" — never matched anything.
 */
class StalkerTitleMatchPolicyTest {

    private val matrix = StalkerTitleMatchPolicy.wantKeys(listOf("The Matrix", null))
    private val bb = StalkerTitleMatchPolicy.wantKeys(listOf("Breaking Bad", "Breaking Bad"))

    @Test
    fun `everyday portal movie names match the TMDB title`() {
        listOf(
            "The Matrix (1999)",
            "EN - The Matrix (1999)",
            "|EN| The Matrix",
            "EN: The Matrix 1999",
            "The Matrix 4K",
            "The Matrix (1999) [MULTI-SUB]",
            "4K-NF - The Matrix",
        ).forEach { assertTrue(StalkerTitleMatchPolicy.movieMatches(it, matrix, year = 1999), "must match: $it") }
    }

    @Test
    fun `a different film or a wrong year does not match`() {
        assertFalse(StalkerTitleMatchPolicy.movieMatches("EN - The Matrix Reloaded (2003)", matrix, 1999), "sequel")
        assertFalse(StalkerTitleMatchPolicy.movieMatches("The Matrix (2021)", matrix, 1999), "year off by 22")
    }

    @Test
    fun `everyday portal series names match`() {
        listOf("Breaking Bad", "EN - Breaking Bad", "Breaking Bad (Hindi)", "|UK| Breaking Bad EN").forEach {
            assertTrue(StalkerTitleMatchPolicy.seriesMatches(it, bb, season = 2), "must match: $it")
        }
    }

    @Test
    fun `a split-season entry is offered only for its own season`() {
        assertTrue(StalkerTitleMatchPolicy.seriesMatches("Breaking Bad S5", bb, season = 5), "S5 entry, S5 asked")
        assertFalse(StalkerTitleMatchPolicy.seriesMatches("Breaking Bad S5", bb, season = 1), "S5 entry, S1 asked")
        assertFalse(StalkerTitleMatchPolicy.seriesMatches("EN - Breaking Bad Season 3", bb, season = 1), "Season 3 entry, S1 asked")
    }

    @Test
    fun `another show does not match`() {
        assertFalse(StalkerTitleMatchPolicy.seriesMatches("Better Call Saul", bb, season = 1))
    }
}
