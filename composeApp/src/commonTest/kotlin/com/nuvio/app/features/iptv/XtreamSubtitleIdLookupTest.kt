package com.nuvio.app.features.iptv

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XtreamSubtitleIdLookupTest {

    private val movie = "xtream:panel.example:vod:100000"
    private val episodeParent = "xtream:panel.example:series:77"

    private fun lookup(
        index: Int? = null,
        panel: Int? = null,
        panelCalls: MutableList<String> = mutableListOf(),
        imdb: (Int, Boolean) -> String? = { tmdb, _ -> if (tmdb == 603) "tt0133093" else null },
    ) = XtreamSubtitleIdLookup(
        indexTmdb = { _, _, _ -> index },
        panelTmdb = { account, isSeries, sid -> panelCalls += "$account/$isSeries/$sid"; panel },
        imdbOf = { tmdb, isSeries -> imdb(tmdb, isSeries) },
    )

    @Test
    fun indexedTmdbIdResolvesWithoutAskingThePanel() = runTest {
        val calls = mutableListOf<String>()
        assertEquals("tt0133093", lookup(index = 603, panelCalls = calls).publicSubtitleVideoId(movie, null, null))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun aMovieTheIndexHasNotSeenYetFallsBackToThePanelTmdbId() = runTest {
        // T5: playlist added this session -> match index has no row yet, but get_vod_info carries tmdb_id.
        val calls = mutableListOf<String>()
        assertEquals("tt0133093", lookup(index = null, panel = 603, panelCalls = calls).publicSubtitleVideoId(movie, null, null))
        assertEquals(listOf("panel.example/false/100000"), calls)
    }

    @Test
    fun anEpisodeFallsBackToThePanelSeriesTmdbIdAndKeepsSeasonAndEpisode() = runTest {
        assertEquals(
            "tt0133093:2:5",
            lookup(index = null, panel = 603).publicSubtitleVideoId(episodeParent, 2, 5),
        )
    }

    @Test
    fun noTmdbIdAnywhereMeansNoPublicId() = runTest {
        assertNull(lookup(index = null, panel = null).publicSubtitleVideoId(movie, null, null))
        assertNull(lookup(index = 0, panel = -1).publicSubtitleVideoId(movie, null, null))
    }

    @Test
    fun aFailingPanelOrTmdbLookupMeansNoPublicIdNotACrash() = runTest {
        val boom = XtreamSubtitleIdLookup(
            indexTmdb = { _, _, _ -> null },
            panelTmdb = { _, _, _ -> error("panel down") },
            imdbOf = { _, _ -> error("tmdb down") },
        )
        assertNull(boom.publicSubtitleVideoId(movie, null, null))
    }

    @Test
    fun nonXtreamIdsAreNotResolved() = runTest {
        assertNull(lookup(index = 603).publicSubtitleVideoId("tt0111161", null, null))
        assertNull(lookup(index = 603).publicSubtitleVideoId("xtream:panel.example:live:5", null, null))
    }
}
