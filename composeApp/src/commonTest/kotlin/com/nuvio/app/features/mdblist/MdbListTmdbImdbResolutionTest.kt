package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: MDBList ratings were fetched only for titles already carrying an IMDb id, so titles
 * from TMDB-keyed add-ons (tmdb:123, bare numeric ids) got ratings on Android TV (which converts
 * TMDB -> IMDb first) but not on phone/iOS, Desktop or Apple TV.
 */
class MdbListTmdbImdbResolutionTest {
    private val active = MdbListSettings(enabled = true, apiKey = "key")
    private val rating = MetaExternalRating(source = "imdb", value = 8.1)

    @Test
    fun `tmdb keyed title is eligible for MDBList ratings`() {
        val meta = MetaDetails(id = "tmdb:603", type = "movie", name = "The Matrix")
        assertTrue(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, active), "tmdb: id must be eligible")
    }

    @Test
    fun `bare numeric and prefixed tmdb ids are eligible`() {
        listOf("603", "movie:603", "series:1399", "TMDB:1399:1:2").forEach { id ->
            val meta = MetaDetails(id = id, type = "movie", name = "x")
            assertTrue(MdbListMetadataService.shouldFetchForMeta(meta, id, active), "id $id")
        }
    }

    @Test
    fun `title with neither imdb nor tmdb id stays ineligible`() {
        val meta = MetaDetails(id = "kitsu:42", type = "series", name = "Anime")
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, active))
    }

    @Test
    fun `gates still apply to tmdb keyed titles`() {
        val meta = MetaDetails(id = "tmdb:603", type = "movie", name = "x")
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, active.copy(enabled = false)))
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, MdbListSettings(enabled = true)))
    }

    @Test
    fun `enrich converts tmdb id to imdb before asking MDBList`() = runTest {
        val lookups = mutableListOf<Pair<Int, String>>()
        val asked = mutableListOf<Pair<String, String>>()
        val meta = MetaDetails(id = "tmdb:1399", type = "series", name = "GoT")

        val result = MdbListMetadataService.enrichMeta(
            meta = meta,
            fallbackItemId = meta.id,
            settings = active,
            tmdbToImdb = { id, type -> lookups += id to type; "tt0944947" },
            fetchRatings = { imdbId, mediaType, _, _ -> asked += imdbId to mediaType; listOf(rating) },
        )

        assertEquals(listOf(1399 to "series"), lookups, "exactly one TMDB lookup")
        assertEquals(listOf("tt0944947" to "show"), asked, "MDBList asked for the converted IMDb id")
        assertEquals(listOf(rating), result.externalRatings)
    }

    @Test
    fun `enrich makes no TMDB lookup when an imdb id is already present`() = runTest {
        var lookups = 0
        val meta = MetaDetails(id = "tmdb:603", type = "movie", name = "x", imdbId = "tt0133093")

        MdbListMetadataService.enrichMeta(
            meta = meta,
            fallbackItemId = meta.id,
            settings = active,
            tmdbToImdb = { _, _ -> lookups++; "tt9999999" },
            fetchRatings = { imdbId, _, _, _ -> assertEquals("tt0133093", imdbId); listOf(rating) },
        )

        assertEquals(0, lookups, "direct IMDb id needs no lookup")
    }

    @Test
    fun `enrich makes no lookup when MDBList is disabled`() = runTest {
        var lookups = 0
        val meta = MetaDetails(id = "tmdb:603", type = "movie", name = "x")
        val result = MdbListMetadataService.enrichMeta(
            meta = meta,
            fallbackItemId = meta.id,
            settings = active.copy(enabled = false),
            tmdbToImdb = { _, _ -> lookups++; "tt0133093" },
            fetchRatings = { _, _, _, _ -> error("must not fetch") },
        )
        assertEquals(0, lookups)
        assertEquals(emptyList(), result.externalRatings)
    }

    @Test
    fun `failed or non imdb lookup yields no ratings request`() = runTest {
        val meta = MetaDetails(id = "tmdb:603", type = "movie", name = "x")
        listOf(null, "", "nm0000206").forEach { lookupResult ->
            val result = MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = meta.id,
                settings = active,
                tmdbToImdb = { _, _ -> lookupResult },
                fetchRatings = { _, _, _, _ -> error("must not fetch for $lookupResult") },
            )
            assertEquals(emptyList(), result.externalRatings, "lookup result $lookupResult")
        }
    }

    @Test
    fun `policy prefers a direct imdb id in TV order`() {
        val direct = MdbListImdbIdPolicy.plan("tt0000001", "tt0000002", "tt0000003", "movie")
        assertEquals(MdbListImdbIdPolicy.Plan.Direct("tt0000001"), direct)
        assertEquals(
            MdbListImdbIdPolicy.Plan.Direct("tt0000002"),
            MdbListImdbIdPolicy.plan("tmdb:603", "tt0000002:1:3", "tt0000003", "movie"),
        )
        assertEquals(
            MdbListImdbIdPolicy.Plan.Direct("tt0000003"),
            MdbListImdbIdPolicy.plan("tmdb:603", "tmdb:603", "tt0000003", "movie"),
        )
    }

    @Test
    fun `policy plans one tmdb lookup from meta id then fallback id`() {
        assertEquals(
            MdbListImdbIdPolicy.Plan.LookupTmdb(603, "movie"),
            MdbListImdbIdPolicy.plan("tmdb:603", "tmdb:999", null, "movie"),
        )
        assertEquals(
            MdbListImdbIdPolicy.Plan.LookupTmdb(1399, "series"),
            MdbListImdbIdPolicy.plan("kitsu:1", "series:1399:1:1", null, "series"),
        )
        assertEquals(MdbListImdbIdPolicy.Plan.None, MdbListImdbIdPolicy.plan("kitsu:1", "", null, "movie"))
        assertEquals(MdbListImdbIdPolicy.Plan.None, MdbListImdbIdPolicy.plan("tmdb:0", "tmdb:abc", "", "movie"))
    }

    @Test
    fun `policy accepts only imdb shaped lookup results`() {
        assertEquals("tt0133093", MdbListImdbIdPolicy.fromLookup(" tt0133093 "))
        assertEquals(null, MdbListImdbIdPolicy.fromLookup(null))
        assertEquals(null, MdbListImdbIdPolicy.fromLookup(""))
        assertEquals(null, MdbListImdbIdPolicy.fromLookup("nm0000206"))
    }
}
