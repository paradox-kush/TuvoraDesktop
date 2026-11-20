package com.nuvio.app.features.notifications

import com.nuvio.app.features.library.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeAlertEnrolmentTest {
    private fun item(id: String, type: String) = LibraryItem(id = id, type = type, name = id, savedAtEpochMs = 0L)

    private fun show(id: String, type: String) =
        TrackedFollowedShow(contentId = id, contentType = type, followedOnIsoDate = "2026-01-01")

    @Test
    fun `only series is an episodic type`() {
        assertTrue(isSeriesLibraryType("series"), "series")
        assertTrue(isSeriesLibraryType(" Series "), "series is case and space insensitive")
        assertFalse(isSeriesLibraryType("tv"), "tv is a live channel")
        assertFalse(isSeriesLibraryType("movie"), "movie")
        assertFalse(isSeriesLibraryType("channel"), "other types")
    }

    @Test
    fun `enrolment from a mixed library keeps only series`() {
        val library = listOf(
            item("s1", "series"),
            item("c1", "tv"),
            item("m1", "movie"),
            item("s2", "series"),
        )
        assertEquals(listOf("s1", "s2"), enrollableSeriesItems(library).map { it.id }, "enrolled ids")
    }

    @Test
    fun `pruning a stored payload drops tv entries and keeps shows`() {
        val stored = listOf(show("s1", "series"), show("c1", "tv"), show("m1", "movie"))
        assertEquals(listOf(show("s1", "series")), pruneToSeriesShows(stored), "pruned list")
        assertEquals(emptyList(), pruneToSeriesShows(emptyList()), "empty payload")
    }
}
