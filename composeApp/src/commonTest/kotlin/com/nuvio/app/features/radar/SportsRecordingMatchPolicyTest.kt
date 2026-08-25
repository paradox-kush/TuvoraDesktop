package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recordings section matched a provider VOD to a fixture by naming — and the ungated event-token
 * fallback let one team's two words accept an unrelated title, so "Arizona Cardinals v Dallas
 * Cowboys" pulled in "…the Untold Story of the Dallas Cowboys Cheerleaders" (device-confirmed).
 * These pin the gated rule: both teams for team fixtures, the event fallback only for event-only sports.
 */
class SportsRecordingMatchPolicyTest {

    /** Word-boundary membership, standing in for the matcher's hits(normalize(title), token). */
    private fun titleMatcher(title: String): (String) -> Boolean {
        val words = title.lowercase().split(Regex("[^a-z0-9]+")).filterNot { it.isEmpty() }.toSet()
        return { token -> token in words }
    }

    private val cardinals = listOf("arizona", "cardinals")
    private val cowboys = listOf("dallas", "cowboys")
    // The backend fills a team fixture's event with "Home vs Away", so its tokens include BOTH teams.
    private val cardinalsVsCowboysEvent = cardinals + cowboys

    @Test
    fun `a team fixture rejects a title naming only one team even via the event string`() {
        val title = "Daughters of the Sexual Revolution: The Untold Story of the Dallas Cowboys Cheerleaders"
        assertFalse(
            SportsRecordingMatchPolicy.accepts(
                homeTokens = cardinals,
                awayTokens = cowboys,
                eventTokens = cardinalsVsCowboysEvent,
                matches = titleMatcher(title),
            ),
            "a Cowboys-only documentary must not match a Cardinals-v-Cowboys fixture",
        )
    }

    @Test
    fun `a team fixture accepts a title naming both teams`() {
        val title = "Arizona Cardinals vs Dallas Cowboys - Full Game"
        assertTrue(
            SportsRecordingMatchPolicy.accepts(cardinals, cowboys, cardinalsVsCowboysEvent, titleMatcher(title)),
            "a full-game recording naming both teams must match",
        )
    }

    @Test
    fun `an event-only fixture accepts a title carrying at least two event words`() {
        val event = listOf("monaco", "grand", "prix")
        val title = "Formula 1 Monaco Grand Prix 2024"
        assertTrue(
            SportsRecordingMatchPolicy.accepts(emptyList(), emptyList(), event, titleMatcher(title)),
            "an event-only fixture keeps the two-event-word fallback (motorsport/golf)",
        )
    }

    @Test
    fun `an event-only fixture rejects a title carrying only one event word`() {
        val event = listOf("monaco", "grand", "prix")
        val title = "Grand Designs"
        assertFalse(
            SportsRecordingMatchPolicy.accepts(emptyList(), emptyList(), event, titleMatcher(title)),
            "one shared word is not enough for an event-only match",
        )
    }
}
