package com.nuvio.app.features.notifications

import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.isEpisodicShowType
import com.nuvio.app.features.library.LibraryItem
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackedShowsTest {
    @BeforeTest
    fun registerOwnSource() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerContentIdPredicate("test-iptv") { it.startsWith("xtream:") || it.startsWith("m3u:") }
    }

    @AfterTest
    fun clearOwnSource() = OwnSourcePolicy.resetForTest()

    private val show = LibraryItem(id = "tt100", type = "series", name = "Severance", savedAtEpochMs = 0L)
    private val anime = LibraryItem(id = "kitsu:1", type = "anime", name = "Frieren", savedAtEpochMs = 0L)
    private val movie = LibraryItem(id = "tt200", type = "movie", name = "Heat", savedAtEpochMs = 0L)
    private val sky = LibraryItem(id = "xtream:acc:live:1", type = "tv", name = "Sky News 1 HD", savedAtEpochMs = 0L)
    private val espn = LibraryItem(id = "m3u:acc:live:9", type = "tv", name = "ESPN News 1 HD", savedAtEpochMs = 0L)
    private val tvShow = LibraryItem(id = "tmdb:1396", type = "tv", name = "Breaking Bad", savedAtEpochMs = 0L)

    private fun tracked(id: String, type: String) =
        TrackedFollowedShow(contentId = id, contentType = type, followedOnIsoDate = "2026-01-01")

    @Test
    fun `only episodic shows are trackable`() {
        assertTrue(isEpisodicShowType("series", "tt100"))
        assertTrue(isEpisodicShowType(" Series ", "tt100"))
        assertTrue(isEpisodicShowType("anime", "kitsu:1"))
        assertTrue(isEpisodicShowType("series", "xtream:acc:series:5"), "a series through a provider id is still a show")
        assertFalse(isEpisodicShowType("movie", "tt200"))
        assertFalse(isEpisodicShowType("movie", "xtream:acc:movie:3"))
        assertFalse(isEpisodicShowType("channel", "tt1"))
        assertFalse(isEpisodicShowType("", "tt1"))
        assertFalse(isEpisodicShowType(null, null))
    }

    @Test
    fun `a tv item with an own-provider id is a live channel and not a show`() {
        assertFalse(isEpisodicShowType("tv", "xtream:acc:live:42"))
        assertFalse(isEpisodicShowType("TV", "m3u:acc:live:9"))
    }

    @Test
    fun `a tv item with a TMDB or Cinemeta style id is a show`() {
        assertTrue(isEpisodicShowType("tv", "tmdb:1396"))
        assertTrue(isEpisodicShowType("tv", "tt0903747"))
    }

    @Test
    fun `enrolment from a mixed library keeps shows and drops channels and movies`() {
        val ids = TrackedShows.enrollable(listOf(sky, show, movie, espn, anime, tvShow)).map { it.id }
        assertEquals(listOf("tt100", "kitsu:1", "tmdb:1396"), ids, "enrolled ids")
    }

    @Test
    fun `pruning a stored payload drops channels and movies and keeps real shows`() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = json.decodeFromString<StoredEpisodeReleaseNotificationsPayload>(
            """{"enabled":true,"followedShows":[
              {"contentId":"tt100","contentType":"series","followedOnIsoDate":"2026-01-01"},
              {"contentId":"xtream:acc:live:1","contentType":"tv","followedOnIsoDate":"2026-01-01"},
              {"contentId":"m3u:acc:live:9","contentType":"tv","followedOnIsoDate":"2026-01-01"},
              {"contentId":"tmdb:1396","contentType":"tv","followedOnIsoDate":"2026-01-01"},
              {"contentId":"tt200","contentType":"movie","followedOnIsoDate":"2026-01-01"}]}""",
        )
        val kept = TrackedShows.prune(payload.followedShows)
        assertEquals(listOf("tt100", "tmdb:1396"), kept.map { it.contentId }, "tv shows with plain ids stay")
        assertEquals("2026-01-01", kept.first().followedOnIsoDate, "a kept show is untouched")
        assertTrue(payload.enabled, "the master switch is untouched")
    }

    @Test
    fun `an empty stored list prunes to nothing`() {
        assertEquals(emptyList(), TrackedShows.prune(emptyList()))
        assertEquals(listOf(tracked("tt100", "series")), TrackedShows.prune(listOf(tracked("tt100", "series"))))
    }

    @Test
    fun `the test notification picks a show and never a channel`() {
        assertEquals(show, TrackedShows.testTarget(listOf(sky, movie, show, espn)))
        assertEquals(tvShow, TrackedShows.testTarget(listOf(sky, tvShow)), "a tv show with a TMDB id is a valid pick")
        assertNull(TrackedShows.testTarget(listOf(sky, espn, movie)), "only channels and movies saved")
    }
}
