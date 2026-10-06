package com.nuvio.app.features.mediaserver.internal.policy

import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy.Decision
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy.ServerState
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLAUDE.md "recurring network work" rule for media-server Home rows: delta (only enabled rows; none
 * enabled = no request), lifecycle-bound (no timer in the policy - it is only asked when Home is RESUMED),
 * cheap (TTL, backoff, minimal fields).
 */
class HomeRefreshPolicyTest {
    private val all = setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED)

    @Test
    fun noEnabledRowsMeansNoRequestEver() {
        assertEquals(Decision.Skip(SkipReason.NO_ROWS_ENABLED), HomeRefreshPolicy.decide(emptySet(), ServerState(), 0, force = false))
        assertEquals(Decision.Skip(SkipReason.NO_ROWS_ENABLED), HomeRefreshPolicy.decide(emptySet(), ServerState(), 0, force = true), "not even a forced pull-to-refresh")
    }

    @Test
    fun theFirstRefreshFetchesExactlyTheEnabledRows() {
        val one = setOf(MediaServerHomeRow.NEXT_UP)
        assertEquals(Decision.Fetch(one), HomeRefreshPolicy.decide(one, ServerState(), 1_000, false), "a delta, not the world")
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, ServerState(), 1_000, false))
    }

    @Test
    fun aFreshRowSetIsNotRefetched() {
        val state = HomeRefreshPolicy.afterSuccess(ServerState(), nowMs = 10_000)
        assertEquals(Decision.Skip(SkipReason.FRESH), HomeRefreshPolicy.decide(all, state, 10_000 + HomeRefreshPolicy.ROW_TTL_MS - 1, false))
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, state, 10_000 + HomeRefreshPolicy.ROW_TTL_MS, false), "aged out")
    }

    @Test
    fun invalidationAndForceBypassTheTtl() {
        val state = HomeRefreshPolicy.afterSuccess(ServerState(), 10_000)
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, state, 11_000, force = true))
        val invalidated = HomeRefreshPolicy.invalidate(state)
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, invalidated, 11_000, false), "a websocket UserDataChanged / own playback report")
        assertFalse(HomeRefreshPolicy.afterSuccess(invalidated, 12_000).invalidated)
    }

    @Test
    fun aServiceUnavailableHonoursRetryAfter() {
        val s = HomeRefreshPolicy.afterFailure(ServerState(), nowMs = 1_000, httpStatus = 503, retryAfterSeconds = 45)
        assertEquals(46_000L, s.blockedUntilMs)
        assertEquals(Decision.Skip(SkipReason.BACKING_OFF), HomeRefreshPolicy.decide(all, s, 20_000, false))
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, s, 46_000, false))
        // an absurd Retry-After is capped
        assertEquals(1_000L + 5 * 60_000L, HomeRefreshPolicy.afterFailure(ServerState(), 1_000, 503, 86_400).blockedUntilMs)
    }

    @Test
    fun otherFailuresBackOffExponentiallyUpToACap() {
        var s = ServerState()
        val delays = mutableListOf<Long>()
        repeat(7) {
            s = HomeRefreshPolicy.afterFailure(s, nowMs = 0, httpStatus = null, retryAfterSeconds = null)
            delays += s.blockedUntilMs!!
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), delays)
    }

    @Test
    fun aServerIsOfflineAfterTwoFailuresAndForgivenOnSuccess() {
        var s = HomeRefreshPolicy.afterFailure(ServerState(), 0, null, null)
        assertFalse(HomeRefreshPolicy.isOffline(s))
        s = HomeRefreshPolicy.afterFailure(s, 40_000, null, null)
        assertTrue(HomeRefreshPolicy.isOffline(s))
        s = HomeRefreshPolicy.afterSuccess(s, 100_000)
        assertFalse(HomeRefreshPolicy.isOffline(s))
        assertNull(s.blockedUntilMs)
    }

    @Test
    fun aForcedRefreshMayProbeAnOfflineServer() {
        val s = HomeRefreshPolicy.afterFailure(ServerState(), 0, null, null)
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, s, 1_000, force = true))
    }

    @Test
    fun theRowQueryIsMinimal() {
        assertEquals(20, HomeRefreshPolicy.rowQuery.limit)
        assertFalse(HomeRefreshPolicy.rowQuery.enableTotalRecordCount)
        assertFalse(HomeRefreshPolicy.rowQuery.fields.contains("MediaSources"), "the heaviest field never rides a row")
    }

    @Test
    fun aServerRowHidesWhatTuvoraContinueWatchingAlreadyShows() {
        assertEquals(listOf("ms:a", "ms:c"), HomeRefreshPolicy.dedupeAgainstContinueWatching(listOf("ms:a", "ms:b", "ms:c"), setOf("ms:b")))
    }

    @Test
    fun aLibraryRowFollowsTheSameTtlAndBackoffGate() {
        assertTrue(HomeRefreshPolicy.shouldFetchList(ServerState(), 1_000, false), "never fetched")
        val fresh = HomeRefreshPolicy.afterSuccess(ServerState(), 1_000)
        assertFalse(HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + 60_000, false), "inside the ttl: no request")
        assertTrue(HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + 60_000, true), "pull-to-refresh")
        assertTrue(HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + HomeRefreshPolicy.ROW_TTL_MS, false), "aged out")
        assertTrue(HomeRefreshPolicy.shouldFetchList(HomeRefreshPolicy.invalidate(fresh), 2_000, false), "invalidated by our own report")
        val failing = HomeRefreshPolicy.afterFailure(ServerState(), 5_000, httpStatus = null, retryAfterSeconds = null)
        assertFalse(HomeRefreshPolicy.shouldFetchList(failing, 5_000 + 10_000, false), "backing off")
        assertTrue(HomeRefreshPolicy.shouldFetchList(failing, 5_000 + 31_000, false))
    }
}
