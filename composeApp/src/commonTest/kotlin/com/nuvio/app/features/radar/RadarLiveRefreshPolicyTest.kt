package com.nuvio.app.features.radar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sports live tick must refresh ONLY leagues/teams with a live-or-imminent fixture — the whole
 * point of the egress fix. The old poll refetched every followed league in full every 2 minutes
 * (one 62-league follower re-pulling ~850 KB/poll, 24/7, was the top Supabase-egress source); the
 * policy narrows each tick to what could actually be live, and returns an empty target set (→ no
 * network) when the slate is idle. kotlin.test → assertEquals(expected, actual, message).
 */
class RadarLiveRefreshPolicyTest {

    private val kickoff = "2026-08-25T18:00:00" // 18:00 UTC
    private fun fixture(id: String, leagueId: String, sport: String = "Soccer", ts: String? = kickoff) =
        RadarFixture(id = id, leagueId = leagueId, sport = sport, home = "H", away = "A", ts = ts)

    private val start = fixture("x", "x").startEpochMs!!

    @Test
    fun league_with_in_progress_fixture_is_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertEquals(setOf("4328"), targets.leagueIds, "a currently-live league must be refreshed")
    }

    @Test
    fun finished_only_league_is_not_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328").copy(status = "Final"))),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertTrue(targets.isEmpty, "a finished-only league must not be refreshed")
    }

    @Test
    fun imminent_kickoff_inside_lead_is_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start - 10 * 60 * 1000L, // 10 min before kick-off, inside the 30-min lead
        )
        assertEquals(setOf("4328"), targets.leagueIds, "an imminent kick-off must join the live tick early")
    }

    @Test
    fun fixture_well_before_lead_is_not_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start - 2 * 60 * 60 * 1000L,
        )
        assertTrue(targets.isEmpty, "a league whose next game is hours away must not be refreshed")
    }

    @Test
    fun fixture_past_max_live_window_is_not_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))), // no finished status
            fixturesByTeam = emptyMap(),
            nowMs = start + 20 * 60 * 60 * 1000L, // a day later
        )
        assertTrue(targets.isEmpty, "a stale, long-past fixture must not keep its league on the live tick")
    }

    @Test
    fun idle_slate_yields_no_targets() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328", "4335"),
            candidateTeamIds = setOf("133604"),
            fixturesByLeague = mapOf(
                "4328" to listOf(fixture("g1", "4328", ts = "2026-08-25T23:00:00")), // hours away
                "4335" to listOf(fixture("g2", "4335").copy(status = "Match Finished")),
            ),
            fixturesByTeam = mapOf("133604" to listOf(fixture("g3", "4335").copy(status = "FT"))),
            nowMs = start, // 18:00
        )
        assertTrue(targets.isEmpty, "nothing live/imminent must produce an empty target set (no fetch)")
    }

    @Test
    fun followed_team_with_live_fixture_is_a_target() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = emptySet(),
            candidateTeamIds = setOf("133604"),
            fixturesByLeague = emptyMap(),
            fixturesByTeam = mapOf("133604" to listOf(fixture("g1", "4328"))),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertEquals(setOf("133604"), targets.teamIds, "a live followed club must be refreshed")
    }

    @Test
    fun live_fixture_for_non_candidate_league_is_ignored() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf(
                "4328" to listOf(fixture("g1", "4328", ts = "2026-08-26T00:00:00")), // not live
                "9999" to listOf(fixture("g2", "9999")), // live, but not a candidate
            ),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertTrue(targets.isEmpty, "only candidate leagues may become targets")
    }
}
