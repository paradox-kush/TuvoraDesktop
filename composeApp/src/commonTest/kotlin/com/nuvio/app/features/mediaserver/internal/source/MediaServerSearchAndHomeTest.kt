package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.HomeShelves
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object FakeTitles : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String) = "${row.name}@$serverName"
    override suspend fun searchMovies(serverName: String) = "Movies@$serverName"
    override suspend fun searchSeries(serverName: String) = "Series@$serverName"
}

class MediaServerSearchAndHomeTest {
    @AfterTest
    fun reset() = MediaServerItemRegistry.reset()

    private val client = FakeClient()
    private fun rig(homeRows: Set<MediaServerHomeRow> = emptySet(), signedIn: Boolean = true, enabled: Boolean = true) = TestRig(clientFactory = { client }).also { r ->
        val e = entry().copy(homeRows = homeRows, enabled = enabled)
        r.store.applyFromRemote(1, listOf(e))
        if (signedIn) r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    // --- search ---

    @Test
    fun searchRowsAreKeyedByTheSourceNotTheUserAndSplitMoviesFromShows() = runTest {
        val rig = rig()
        client.searchHits = listOf(item("m1", "Matrix"), item("m2", "Matrix 2"), item("s1", "Matrix Show", "Series"))
        val rows = MediaServerSearchProvider(rig.store, rig.services, FakeTitles).search("matrix")
        assertEquals(listOf("ms:jellyfin:$M:search:movies", "ms:jellyfin:$M:search:series"), rows.map { it.key })
        assertFalse(rows.any { it.key.contains(U) }, "a re-login must not orphan the viewer's choices")
        assertEquals(listOf(2, 1), rows.map { it.items.size })
        assertEquals("Movies@Home", rows[0].title)
        assertEquals(CatalogTarget.Source("jellyfin:$M", "search:movies", "movie"), rows[0].target)
        assertNotNull(MediaServerItemRegistry.get("ms:jellyfin:$M:$U:movie:m1"), "hits are registered so a tap plays")
    }

    @Test
    fun aSignedOutDisabledOrAddresslessServerIsNeverSearched() = runTest {
        for (r in listOf(rig(signedIn = false), rig(enabled = false))) {
            val p = MediaServerSearchProvider(r.store, r.services, FakeTitles)
            assertFalse(p.isEnabled()); assertNull(p.sourceSignature())
            assertTrue(p.search("x").isEmpty())
        }
        val noAddress = TestRig(clientFactory = { client }).also { r -> val e = entry(address = null); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("t")) }
        assertFalse(MediaServerSearchProvider(noAddress.store, noAddress.services, FakeTitles).isEnabled())
    }

    @Test
    fun aBlankQueryOrAFailingServerAddsNothingAndNeverThrows() = runTest {
        val rig = rig()
        val p = MediaServerSearchProvider(rig.store, rig.services, FakeTitles)
        assertTrue(p.search("   ").isEmpty())
        client.failWith = MediaServerException.Unreachable("down")
        assertTrue(p.search("x").isEmpty())
        client.failWith = MediaServerException.Http(401)
        assertTrue(p.search("x").isEmpty())
        assertFalse(rig.services.isSignedIn(rig.store.current().single()), "a revoked token surfaces as signed out")
    }

    @Test
    fun theSignatureChangesWithTheSignInStateAndNeverContainsACredential() = runTest {
        val rig = rig(signedIn = false)
        val p = MediaServerSearchProvider(rig.store, rig.services, FakeTitles)
        assertNull(p.sourceSignature())
        rig.credentials.save(rig.store.current().single().serverKey, StoredCredential("TOKEN-1"))
        rig.services.notifyCredentialsChanged()
        val sig = p.sourceSignature()
        assertNotNull(sig)
        assertFalse(sig.contains("TOKEN-1") || sig.contains(M) || sig.contains(U), "a digest: $sig")
        assertEquals(sig, p.sourceSignatureChanges().first())
    }

    // --- home ---

    private fun contributor(rig: TestRig, cw: Set<String> = emptySet()) =
        MediaServerHomeContributor(rig.store, rig.services, { rig.nowMs }, { cw }, FakeTitles)

    private val movie = item("m1", "Matrix")
    private val show = item("s1", "Severance", "Series")
    private val episode = item("e1", "Pilot", "Episode") { it.copy(seriesId = "s1", seriesName = "Severance", parentIndexNumber = 1, indexNumber = 1) }

    @Test
    fun nothingEnabledMeansNoRowsAndNoRequestEver() = runTest {
        val rig = rig(homeRows = emptySet())
        val c = contributor(rig)
        assertTrue(c.sections(false).isEmpty()); assertTrue(c.sections(true).isEmpty())
        assertEquals(0, client.homeCalls, "D2: a server's own rows are opt-in - not even a forced refresh asks")
    }

    @Test
    fun aSignedOutOrDisabledServerContributesNothing() = runTest {
        assertTrue(contributor(rig(setOf(MediaServerHomeRow.NEXT_UP), signedIn = false)).sections(false).isEmpty())
        assertTrue(contributor(rig(setOf(MediaServerHomeRow.NEXT_UP), enabled = false)).sections(false).isEmpty())
        assertEquals(0, client.homeCalls)
    }

    @Test
    fun enabledRowsBecomeStableKeyedSectionsAndOnlyThoseRowsAreFetched() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(nextUp = listOf(episode), recentlyAdded = listOf(movie, show))
        val sections = contributor(rig).sections(false)
        assertEquals(setOf(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED), client.lastHomeRows, "the delta: only what the user enabled")
        assertEquals(listOf("ms:jellyfin:$M:next_up", "ms:jellyfin:$M:recently_added"), sections.map { it.key })
        assertFalse(sections.any { it.key.contains(U) })
        assertEquals(listOf("NEXT_UP@Home", "RECENTLY_ADDED@Home"), sections.map { it.title })
        assertEquals(listOf("ms:jellyfin:$M:$U:series:s1"), sections[0].items.map { it.id }, "an episode shelf item opens its series")
        assertEquals(CatalogTarget.Source("jellyfin:$M", "next_up", "movie"), sections[0].target)
        assertTrue(sections[1].hasMore)
    }

    @Test
    fun theTtlGatesRefetchAndTheCachedRowsAreReturned() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(recentlyAdded = listOf(movie))
        val c = contributor(rig)
        assertEquals(1, c.sections(false).size)
        rig.nowMs += 60_000
        assertEquals(1, c.sections(false).size)
        assertEquals(1, client.homeCalls, "fresh: no request at all")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        assertEquals(2, client.homeCalls)
        c.sections(true)
        assertEquals(3, client.homeCalls, "pull-to-refresh bypasses the ttl")
        c.invalidate("jellyfin:$M")
        c.sections(false)
        assertEquals(4, client.homeCalls, "a UserDataChanged / our own playback report")
    }

    @Test
    fun aFailingServerBacksOffThenHidesItsRowsWithoutARetryLoop() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(recentlyAdded = listOf(movie))
        val c = contributor(rig)
        c.sections(false)
        client.failWith = MediaServerException.Unreachable("down")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        assertEquals(1, c.sections(false).size, "first failure: the stale rows stay")
        val callsAfterFirst = client.homeCalls
        assertEquals(1, c.sections(false).size)
        assertEquals(callsAfterFirst, client.homeCalls, "backing off: no request")
        rig.nowMs += 31_000
        assertTrue(c.sections(false).isEmpty(), "second failure: the server counts as offline, rows hidden")
        assertTrue(c.isOffline("jellyfin:$M:$U"))
        val calls = client.homeCalls
        repeat(3) { c.sections(false) }
        assertEquals(calls, client.homeCalls, "an offline server is not retried in a loop")
    }

    @Test
    fun aServiceUnavailableHonoursRetryAfter() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        val c = contributor(rig)
        client.failWith = MediaServerException.Http(503, retryAfterSeconds = 120)
        c.sections(false)
        val calls = client.homeCalls
        rig.nowMs += 100_000
        c.sections(false)
        assertEquals(calls, client.homeCalls, "still inside the Retry-After window")
        client.failWith = null
        client.shelves = HomeShelves(nextUp = listOf(episode))
        rig.nowMs += 30_000
        assertEquals(1, c.sections(false).size)
    }

    @Test
    fun aRevokedTokenDropsTheSession() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        client.failWith = MediaServerException.Http(401)
        contributor(rig).sections(false)
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun itemsTuvoraContinueWatchingAlreadyShowsAreHiddenFromAServerRowButNotFromRecentlyAdded() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(continueWatching = listOf(movie, show), recentlyAdded = listOf(movie))
        val sections = contributor(rig, cw = setOf("ms:jellyfin:$M:$U:movie:m1")).sections(false)
        assertEquals(listOf("ms:jellyfin:$M:$U:series:s1"), sections.single { it.target is CatalogTarget.Source && (it.target as CatalogTarget.Source).listId == "continue_watching" }.items.map { it.id })
        assertEquals(1, sections.single { (it.target as CatalogTarget.Source).listId == "recently_added" }.items.size)
        val all = contributor(rig(setOf(MediaServerHomeRow.CONTINUE_WATCHING)), cw = setOf("ms:jellyfin:$M:$U:movie:m1", "ms:jellyfin:$M:$U:series:s1")).sections(false)
        assertTrue(all.isEmpty(), "an emptied row is not shown")
    }

    @Test
    fun seeAllServesPagesAndOwnsOnlyItsSources() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.searchHits = (1..100).map { item("m$it", "M$it") }
        val c = contributor(rig)
        assertTrue(c.ownsSource("jellyfin:$M")); assertFalse(c.ownsSource("emby:other"))
        val page = c.loadSourcePage(CatalogTarget.Source("jellyfin:$M", "recently_added", "movie"), skip = null)
        assertEquals(100, page.items.size)
        assertEquals(100, page.nextSkip, "a full page means there may be more")
        client.searchHits = listOf(item("m1", "M1"))
        val last = c.loadSourcePage(CatalogTarget.Source("jellyfin:$M", "library:view1", "movie"), skip = 100)
        assertNull(last.nextSkip)
        val unknown = c.loadSourcePage(CatalogTarget.Source("emby:other", "recently_added", "movie"), null)
        assertTrue(unknown.items.isEmpty())
    }
}
