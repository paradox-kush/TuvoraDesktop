package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The channel matcher listed wrong-sport channels for a game because a single shared team word scored
 * a match: the NFL "Arizona Cardinals v Dallas Cowboys" surfaced "US (MLB) St. Louis Cardinals" and
 * "US (MLB) Arizona Diamondbacks" (device-confirmed on the TV build). These pin the cross-sport guard.
 */
class SportsChannelMatchPolicyTest {

    /** Word-boundary membership, standing in for the matcher's hits(normalize(name), token). */
    private fun matcher(text: String): (String) -> Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filterNot { it.isEmpty() }.toSet()
        return { token -> token in words }
    }

    // Arizona Cardinals (home) v Dallas Cowboys (away), league NFL.
    private val home = listOf("arizona", "cardinals")
    private val away = listOf("dallas", "cowboys")
    private val keywords = listOf("nfl")

    private fun name(text: String, generic: Boolean = false) =
        SportsChannelMatchPolicy.nameScore(home, away, keywords, emptyList(), generic, matcher(text))

    @Test
    fun `a wrong-sport MLB channel sharing one team word does not surface`() {
        assertEquals(0, name("US (MLB) St. Louis Cardinals"), "an MLB Cardinals channel must not match an NFL Cardinals game")
        assertEquals(0, name("US (MLB) Arizona Diamondbacks"), "an MLB Arizona channel must not match an NFL Arizona game")
    }

    @Test
    fun `both teams still score highest`() {
        assertEquals(50, name("NFL Network Arizona Cardinals vs Dallas Cowboys"), "both teams present is the strongest name signal")
    }

    @Test
    fun `the league keyword still matches`() {
        assertEquals(25, name("US: NFL RedZone HD"), "a channel carrying the league keyword matches")
    }

    @Test
    fun `a same-sport single-team channel still matches`() {
        // No competing league marker, so the one-team tier still counts (e.g. a team channel).
        assertEquals(12, name("Dallas Cowboys TV"), "a one-team channel with no competing league still matches")
    }

    @Test
    fun `a generic sports channel keeps its weak score`() {
        assertEquals(8, name("beIN Sports 1", generic = true), "a generic sports channel keeps the weak tier")
    }

    @Test
    fun `programme scoring gates a wrong-sport single-team hit too`() {
        val prog = { text: String -> SportsChannelMatchPolicy.programmeScore(home, away, keywords, emptyList(), matcher(text)) }
        assertEquals(0, prog("MLB: St. Louis Cardinals at Philadelphia Phillies"), "an MLB programme must not match an NFL game")
        assertEquals(25, prog("Dallas Cowboys pre-game"), "a same-sport one-team programme still matches weakly")
        assertEquals(70, prog("NFL coverage: Cardinals build-up"), "one team plus the league keyword is a strong programme hit")
        assertEquals(100, prog("Arizona Cardinals vs Dallas Cowboys"), "both teams is the strongest programme hit")
    }

    // --- B2: confidence — CONFIRMED (this fixture is on) vs LEAGUE (channel only carries the competition) ---

    private fun nameConf(text: String, generic: Boolean = false) =
        SportsChannelMatchPolicy.scoreName(home, away, keywords, emptyList(), generic, matcher(text)).confidence

    private fun progConf(text: String) =
        SportsChannelMatchPolicy.scoreProgramme(home, away, keywords, emptyList(), matcher(text)).confidence

    @Test
    fun `both teams name hit is CONFIRMED while keyword-only and single-team are LEAGUE`() {
        assertEquals(MatchConfidence.CONFIRMED, nameConf("NFL Network Arizona Cardinals vs Dallas Cowboys"), "both teams proves the fixture")
        assertEquals(MatchConfidence.LEAGUE, nameConf("US: NFL RedZone HD"), "a league keyword only proves the competition")
        assertEquals(MatchConfidence.LEAGUE, nameConf("Dallas Cowboys TV"), "a single team is not the fixture")
        assertEquals(MatchConfidence.LEAGUE, nameConf("beIN Sports 1", generic = true), "a generic sports channel is a guess")
    }

    @Test
    fun `an event-only name hit is CONFIRMED`() {
        val event = listOf("monaco", "grand", "prix")
        val c = SportsChannelMatchPolicy.scoreName(emptyList(), emptyList(), emptyList(), event, false, matcher("Monaco Grand Prix F1")).confidence
        assertEquals(MatchConfidence.CONFIRMED, c, "the specific event title proves this event")
    }

    @Test
    fun `both teams programme is CONFIRMED while one-sided and keyword-only are LEAGUE`() {
        assertEquals(MatchConfidence.CONFIRMED, progConf("Arizona Cardinals vs Dallas Cowboys"), "both teams proves the fixture")
        assertEquals(MatchConfidence.LEAGUE, progConf("NFL coverage: Cardinals build-up"), "one team plus keyword only carries the league")
        assertEquals(MatchConfidence.LEAGUE, progConf("US: NFL RedZone"), "a keyword-only programme only carries the league")
        assertEquals(MatchConfidence.LEAGUE, progConf("Dallas Cowboys pre-game"), "a one-team programme is not the fixture")
    }

    @Test
    fun `the Scored score equals the legacy Int score for every tier`() {
        for (text in listOf("Arizona Cardinals vs Dallas Cowboys", "US: NFL RedZone HD", "Dallas Cowboys TV", "US (MLB) St. Louis Cardinals")) {
            assertEquals(
                SportsChannelMatchPolicy.nameScore(home, away, keywords, emptyList(), false, matcher(text)),
                SportsChannelMatchPolicy.scoreName(home, away, keywords, emptyList(), false, matcher(text)).score,
                "scoreName.score must equal nameScore for '$text'",
            )
        }
    }
}
