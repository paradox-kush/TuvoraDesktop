package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.ContributedRowDeclaration
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private object Titles : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String) = "$row@$serverName"
    override suspend fun searchMovies(serverName: String) = "movies@$serverName"
    override suspend fun searchSeries(serverName: String) = "series@$serverName"
}

class MediaServerLibrariesPolicyTest {
    private fun view(id: String?, name: String?, type: String?) = ItemDto(id = id, name = name, collectionType = type)

    @Test
    fun onlyMovieAndShowLibrariesAreBrowsable() {
        val libs = MediaServerLibraries.browsable(
            listOf(
                view("1", "Movies", "movies"), view("2", "Shows", "tvshows"), view("3", "Music", "music"), view("4", "Mixed", null),
                view("5", "Photos", "homevideos"), view("6", "Live", "livetv"), view("7", "Playlists", "playlists"), view("8", "Collections", "boxsets"),
                view(null, "No id", "movies"), view("9", null, "movies"),
            ),
        )
        assertEquals(listOf("1" to MediaServerLibraries.Kind.MOVIES, "2" to MediaServerLibraries.Kind.SERIES, "4" to MediaServerLibraries.Kind.MIXED), libs.map { it.id to it.kind })
        assertEquals(listOf("Movies", "Shows", "Mixed"), libs.map { it.name })
    }
}

class MediaServerHomeLibrariesTest {
    private val client = FakeClient()
    private val movie = item("m1", "Matrix")

    private fun rig(homeRows: Set<MediaServerHomeRow> = emptySet(), libraries: Map<String, String> = emptyMap(), signedIn: Boolean = true) =
        TestRig(clientFactory = { client }).also { r ->
            val e = entry().copy(homeRows = homeRows, homeLibraries = libraries)
            r.store.applyFromRemote(1, listOf(e))
            r.store.update(e.key) { it.copy(homeRows = homeRows, homeLibraries = libraries) }
            if (signedIn) r.credentials.save(e.serverKey, StoredCredential("t"))
        }

    private fun contributor(rig: TestRig) = MediaServerHomeContributor(rig.store, rig.services, { rig.nowMs }, { emptySet() }, Titles)

    @Test
    fun aLibraryOnHomeIsARowOfItsOwnWithAStableKeyAndASeeAllTarget() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = listOf(movie)
        val sections = contributor(rig).sections(false)
        val row = sections.single()
        assertEquals("ms:jellyfin:$M:library:view1", row.key)
        assertFalse(row.key.contains(U), "keys never carry a user id")
        assertEquals("Films", row.title); assertEquals("Home", row.subtitle)
        assertEquals(CatalogTarget.Source("jellyfin:$M", "library:view1", "movie"), row.target)
        assertEquals(listOf("ms:jellyfin:$M:$U:movie:m1"), row.items.map { it.id })
        assertEquals(0, client.homeCalls, "libraries are their own query - the opt-in shelves are not asked for")
    }

    @Test
    fun aLibraryRowIsTtlGatedAndHonoursTheBackoff() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = listOf(movie)
        val c = contributor(rig)
        c.sections(false); c.sections(false)
        assertEquals(1, client.itemQueries.size, "fresh: one request for two visits")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        assertEquals(2, client.itemQueries.size)
        c.invalidate("jellyfin:$M")
        c.sections(false)
        assertEquals(3, client.itemQueries.size, "an own playback report invalidates it")
        val q = client.itemQueries.last()
        assertEquals("view1", q.parentId); assertEquals(HomeRefreshPolicy.ROW_LIMIT, q.limit); assertFalse(q.enableTotalRecordCount)
    }

    @Test
    fun anEmptyOrOfflineLibraryRowIsHiddenAndNeverRetriedInALoop() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = emptyList()
        val c = contributor(rig)
        assertTrue(c.sections(false).isEmpty(), "an empty library shows no row")
        client.failWith = com.nuvio.app.features.mediaserver.internal.client.MediaServerException.Unreachable("down")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        rig.nowMs += 31_000
        c.sections(false)
        val calls = client.itemQueries.size
        repeat(4) { c.sections(false) }
        assertEquals(calls, client.itemQueries.size, "backed off after the failures")
    }

    @Test
    fun declaredRowsListEveryConfiguredRowRegardlessOfItsItems() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP), libraries = mapOf("view1" to "Films"))
        val c = contributor(rig)
        assertEquals("Next Up", c.declaredRows().first().title, "before the localized titles are fetched: an English stand-in, never blocking resource I/O")
        c.prepareDeclaredRows()
        val declared = c.declaredRows()
        assertEquals(
            listOf(
                ContributedRowDeclaration("ms:jellyfin:$M:next_up", "NEXT_UP@Home", "Home"),
                ContributedRowDeclaration("ms:jellyfin:$M:library:view1", "Films", "Home"),
            ),
            declared,
        )
        assertEquals(0, client.homeCalls + client.itemQueries.size, "declaring rows is network-free")
    }

    @Test
    fun aDisabledOrUnconfiguredServerDeclaresNothing() {
        assertTrue(contributor(rig()).declaredRows().isEmpty())
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        rig.store.update(rig.store.current().single().key) { it.copy(enabled = false) }
        assertTrue(contributor(rig).declaredRows().isEmpty())
    }
}
