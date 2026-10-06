package com.nuvio.app.features.mediaserver.internal.policy

import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserDialect
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ItemKind
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchLookupPolicyTest {
    private val ids = ExternalIds(tmdb = "603", imdb = "tt0133093")

    @Test
    fun embyFiltersByProviderIdOnTheServer() {
        val q = MatchLookupPolicy.query(MediaBrowserDialect.EMBY, ItemKind.MOVIE, ids, title = "The Matrix", year = 1999)
        assertEquals(Query.ByProviderId(listOf("tmdb.603", "imdb.tt0133093"), "Movie"), q)
    }

    @Test
    fun jellyfinHasNoProviderIdFilterSoItSearchesByTitleAndYear() {
        val q = MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, title = " The Matrix ", year = 1999)
        assertEquals(Query.ByTitle("The Matrix", listOf(1998, 1999, 2000), "Movie"), q)
        assertEquals(Query.ByTitle("Severance", emptyList(), "Series"), MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.SERIES, ExternalIds(tmdb = "95396"), "Severance", null))
    }

    @Test
    fun noIdsOrNoTitleMeansNoLookup() {
        assertNull(MatchLookupPolicy.query(MediaBrowserDialect.EMBY, ItemKind.MOVIE, ExternalIds(), "x", 2000))
        assertNull(MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, null, 1999), "Jellyfin needs a title to search")
        assertNull(MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, "  ", 1999))
    }

    private fun item(id: String, vararg providers: Pair<String, String?>) = ItemDto(id = id, providerIds = providers.toMap())

    @Test
    fun aTitleSearchCandidateMustAgreeOnAnExternalId() {
        val candidates = listOf(
            item("1", "Tmdb" to "603", "Imdb" to "tt0133093"),
            item("2", "Tmdb" to "604"),                       // a sequel with a similar title
            item("3", "tmdb" to "603"),                       // key casing differs per server version
            item("4"),                                        // no provider ids at all
            item("5", "Imdb" to "TT0133093"),                 // id casing
        )
        assertEquals(listOf("1", "3", "5"), MatchLookupPolicy.verify(candidates, ids).map { it.id })
    }

    @Test
    fun blankIdsNeverMatchBlankProviderIds() {
        assertEquals(emptyList(), MatchLookupPolicy.verify(listOf(item("1", "Tmdb" to "")), ExternalIds(tmdb = "")))
        assertEquals(emptyList(), MatchLookupPolicy.verify(listOf(item("1", "Tmdb" to null)), ExternalIds(tmdb = "603")))
    }
}
