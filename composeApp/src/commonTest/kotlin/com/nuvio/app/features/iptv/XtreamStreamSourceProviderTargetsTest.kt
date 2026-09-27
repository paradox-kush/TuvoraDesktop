package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** B19: a playlist whose settings offer nothing for a type gets no matched-source group for it. */
class XtreamStreamSourceProviderTargetsTest {

    private fun account(
        id: String,
        types: Set<String> = ALL_CONTENT_TYPES,
        selections: CategorySelections = CategorySelections(),
        sourceType: String = SOURCE_TYPE_XTREAM,
        enabled: Boolean = true,
    ) = XtreamAccount(
        id = id, name = id, baseUrl = "http://h", username = "u", password = "p",
        enabled = enabled, sourceType = sourceType, contentTypes = types, categorySelections = selections,
    )

    @Test
    fun `playlists that offer the type are match targets`() {
        val accounts = listOf(
            account("all"),
            account("noMovies", types = setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_SERIES)),
            account("noMovieCategories", selections = CategorySelections(movies = emptyList())),
            account("stalker", sourceType = SOURCE_TYPE_STALKER),
            account("m3u", sourceType = SOURCE_TYPE_M3U_URL),
            account("disabled", enabled = false),
        )

        assertEquals(
            listOf("all", "stalker"),
            XtreamStreamSourceProvider.matchTargets(accounts, "movie").map { it.id },
            "movie targets",
        )
        assertEquals(
            listOf("all", "noMovies", "noMovieCategories", "stalker"),
            XtreamStreamSourceProvider.matchTargets(accounts, "series").map { it.id },
            "series targets",
        )
        assertEquals(emptyList(), XtreamStreamSourceProvider.matchTargets(accounts, "tv").map { it.id }, "live never matches")
    }
}
