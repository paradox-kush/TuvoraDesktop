package com.nuvio.app.features.iptv.match

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B78 (egress): the match-map pull re-downloaded the whole per-provider `iptv_tmdb_map` every app
 * session. These pin the delta contract: bootstrap once, then fetch only what moved.
 */
class MatchMapPullPolicyTest {

    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    /** A remote table that honours the delta filter + paging the way PostgREST would. */
    private class FakeRemote(var rows: List<RemoteMapping>) : MatchMapRemote {
        val calls = mutableListOf<Long?>()
        override suspend fun fetchPage(provider: String, sinceMs: Long?, offset: Int, limit: Int): List<RemoteMapping> {
            calls += sinceMs
            return rows.filter { sinceMs == null || it.updatedAtMs >= sinceMs }
                .sortedBy { it.updatedAtMs }
                .drop(offset).take(limit)
        }
    }

    /** The local mirror + its cursor, applied together (as MatchDb does in one transaction). */
    private class FakeStore : MatchMapStore {
        val rows = mutableMapOf<Pair<MatchKind, Int>, RemoteMapping>()
        val cursors = mutableMapOf<Pair<String, String>, MatchMapCursor>()
        override suspend fun readCursor(owner: String, provider: String) = cursors[owner to provider]
        override suspend fun applyPage(owner: String, provider: String, rows: List<RemoteMapping>, cursor: MatchMapCursor): Int {
            var applied = 0
            for (r in rows) {
                val local = this.rows[r.kind to r.tmdb]
                if (MatchMapPullPolicy.shouldApply(r.updatedAtMs, local?.updatedAtMs)) { this.rows[r.kind to r.tmdb] = r; applied++ }
            }
            cursors[owner to provider] = cursor
            return applied
        }
        fun wipe() { rows.clear(); cursors.clear() }
    }

    private fun remoteRows(n: Int, newest: Long) = (0 until n).map { i ->
        // Spread over ~n hours, newest last.
        RemoteMapping(MatchKind.MOVIE, tmdb = 1000 + i, sid = i, matchedName = "t$i", updatedAtMs = newest - (n - 1 - i) * 60 * minute)
    }

    @Test
    fun `second session with an unchanged remote makes only a delta query and re-applies nothing`() = runTest {
        val remote = FakeRemote(remoteRows(200, newest = t0))
        val store = FakeStore()

        val first = MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)
        assertEquals(true, first.full, "first session bootstraps with a full pull")
        assertEquals(200, first.applied)

        val second = MatchMapPuller.pull("u1", "p", nowMs = t0 + 2 * 60 * minute, store = store, remote = remote)
        assertEquals(false, second.full, "second session must be a delta pull, not the whole table")
        assertEquals(t0 - MatchMapPullPolicy.OVERLAP_MS, remote.calls.last(), "delta is bounded by the mark minus the overlap")
        assertTrue(second.fetched <= 1, "only the overlap window re-downloads, got ${second.fetched} of 200")
        assertEquals(0, second.applied, "nothing changed remotely, nothing is re-applied")
    }

    @Test
    fun `a relaunch inside the min interval makes no request at all`() = runTest {
        val remote = FakeRemote(remoteRows(50, newest = t0))
        val store = FakeStore()
        MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)
        val callsAfterFirst = remote.calls.size

        val relaunch = MatchMapPuller.pull("u1", "p", nowMs = t0 + 5 * minute, store = store, remote = remote)

        assertEquals(0, relaunch.requests, "nothing is due, so nothing is fetched")
        assertEquals(callsAfterFirst, remote.calls.size)
    }

    @Test
    fun `a row another device added since is picked up by the delta`() = runTest {
        val remote = FakeRemote(remoteRows(100, newest = t0))
        val store = FakeStore()
        MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)

        val added = RemoteMapping(MatchKind.SERIES, tmdb = 42, sid = 7, matchedName = "new", updatedAtMs = t0 + 30 * minute)
        remote.rows = remote.rows + added
        val next = MatchMapPuller.pull("u1", "p", nowMs = t0 + 60 * minute, store = store, remote = remote)

        assertEquals(1, next.applied)
        assertEquals(added, store.rows[MatchKind.SERIES to 42])
        assertEquals(t0 + 30 * minute, store.cursors["u1" to "p"]!!.markMs)
    }

    @Test
    fun `a wiped mirror loses its cursor and re-bootstraps in full`() = runTest {
        val remote = FakeRemote(remoteRows(80, newest = t0))
        val store = FakeStore()
        MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)

        store.wipe()
        val after = MatchMapPuller.pull("u1", "p", nowMs = t0 + 60 * minute, store = store, remote = remote)

        assertEquals(true, after.full)
        assertEquals(80, after.applied)
    }

    @Test
    fun `another account on the same provider starts from zero`() = runTest {
        val remote = FakeRemote(remoteRows(10, newest = t0))
        val store = FakeStore()
        MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)

        val other = MatchMapPuller.pull("u2", "p", nowMs = t0 + 60 * minute, store = store, remote = remote)
        assertEquals(true, other.full)
    }

    @Test
    fun `full pulls page past the PostgREST row cap and advance the mark to the newest row`() = runTest {
        val n = MatchMapPullPolicy.PAGE_SIZE + 5
        val remote = FakeRemote(remoteRows(n, newest = t0))
        val store = FakeStore()

        val first = MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)

        assertEquals(2, first.requests)
        assertEquals(n, first.applied)
        assertEquals(t0, store.cursors["u1" to "p"]!!.markMs)
    }

    @Test
    fun `a weekly full resync heals rows pushed late with an old timestamp`() = runTest {
        val remote = FakeRemote(remoteRows(10, newest = t0))
        val store = FakeStore()
        MatchMapPuller.pull("u1", "p", nowMs = t0 + minute, store = store, remote = remote)
        // Made offline days ago, pushed only now: its timestamp is far behind our mark.
        val late = RemoteMapping(MatchKind.MOVIE, tmdb = 9, sid = 9, matchedName = "late", updatedAtMs = t0 - 3 * 24 * 60 * minute)
        remote.rows = remote.rows + late

        MatchMapPuller.pull("u1", "p", nowMs = t0 + 60 * minute, store = store, remote = remote)
        assertNull(store.rows[MatchKind.MOVIE to 9], "a client-clock delta cannot see it (known, bounded gap)")

        val weekly = MatchMapPuller.pull("u1", "p", nowMs = t0 + MatchMapPullPolicy.FULL_RESYNC_INTERVAL_MS + minute, store = store, remote = remote)
        assertEquals(true, weekly.full)
        assertEquals(late, store.rows[MatchKind.MOVIE to 9])
    }

    @Test
    fun `a far-future remote timestamp cannot push the mark past now plus tolerance`() {
        val now = t0
        val next = MatchMapPullPolicy.advance(cursor = null, pageMaxUpdatedAtMs = now + 365L * 24 * 60 * minute, full = true, nowMs = now)
        assertEquals(now + MatchMapPullPolicy.FUTURE_TOLERANCE_MS, next.markMs)
    }

    @Test
    fun `a cursor from the future is distrusted and re-bootstrapped`() {
        val poisoned = MatchMapCursor(markMs = t0 + 24 * 60 * minute, lastPullAtMs = t0 - 60 * minute, lastFullPullAtMs = t0 - 60 * minute)
        val plan = MatchMapPullPolicy.plan(poisoned, nowMs = t0)
        assertIs<MatchMapPullPlan.Fetch>(plan)
        assertNull(plan.sinceMs)
    }

    @Test
    fun `the mark never moves backwards on a delta`() {
        val cursor = MatchMapCursor(markMs = t0, lastPullAtMs = t0, lastFullPullAtMs = t0)
        val next = MatchMapPullPolicy.advance(cursor, pageMaxUpdatedAtMs = t0 - 5 * minute, full = false, nowMs = t0 + 60 * minute)
        assertEquals(t0, next.markMs)
        assertEquals(t0, next.lastFullPullAtMs)
        assertEquals(t0 + 60 * minute, next.lastPullAtMs)
    }

    @Test
    fun `local rows equal or newer than the remote row win`() {
        assertTrue(MatchMapPullPolicy.shouldApply(remoteUpdatedAtMs = 2, localUpdatedAtMs = null))
        assertTrue(MatchMapPullPolicy.shouldApply(remoteUpdatedAtMs = 2, localUpdatedAtMs = 1))
        assertEquals(false, MatchMapPullPolicy.shouldApply(remoteUpdatedAtMs = 2, localUpdatedAtMs = 2))
        assertEquals(false, MatchMapPullPolicy.shouldApply(remoteUpdatedAtMs = 1, localUpdatedAtMs = 2))
    }
}
