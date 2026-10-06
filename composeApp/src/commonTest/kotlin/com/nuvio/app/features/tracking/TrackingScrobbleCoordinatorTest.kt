package com.nuvio.app.features.tracking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates one provider failure`() = runBlocking {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, failure = IllegalStateException("offline"))
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161"),
            ),
            progressPercent = 42.5,
        )

        val failures = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing),
            profileId = 2,
            action = TrackingScrobbleAction.PAUSE,
            event = event,
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(listOf(TrackingProviderId.SIMKL), failures.map(TrackingScrobbleFailure::providerId))
    }

    @Test
    fun `seek fanout targets only providers that restart scrobbles`() = runBlocking {
        val trakt = FakeScrobbler(
            providerId = TrackingProviderId.TRAKT,
            seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART,
        )
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161"),
            ),
            progressPercent = 55.0,
        )

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(trakt, simkl),
            profileId = 2,
            action = TrackingScrobbleAction.STOP,
            event = event,
        )

        assertEquals(1, trakt.callCount)
        assertEquals(0, simkl.callCount)
    }

    @Test
    fun `a media-server item is never scrobbled while a matched title still is`() = runBlocking {
        com.nuvio.app.core.contracts.OwnSourcePolicy.resetForTest()
        com.nuvio.app.core.contracts.OwnSourcePolicy.registerScrobbleExclusion("test") { it.startsWith("ms:") }
        try {
            val trakt = FakeScrobbler(TrackingProviderId.TRAKT)
            val serverItem = TrackingScrobbleEvent(
                media = TrackingMediaReference(
                    kind = TrackingMediaKind.MOVIE,
                    title = "Test Movie",
                    year = 2024,
                    catalog = TrackingCatalogReference(contentId = "ms:jellyfin:m1:u1:movie:abc", contentType = "movie"),
                ),
                progressPercent = 10.0,
            )
            val failures = dispatchTrackingScrobble(listOf(trakt), 1, TrackingScrobbleAction.START, serverItem)
            assertEquals(0, trakt.callCount, "owner decision 2026-10-06: no Trakt scrobble for ms: items in v1")
            assertEquals(emptyList(), failures.map(TrackingScrobbleFailure::providerId))
            assertEquals(0, dispatchTrackingSeekScrobble(listOf(trakt), 1, TrackingScrobbleAction.STOP, serverItem).size)

            val matched = serverItem.copy(
                media = serverItem.media.copy(
                    ids = TrackingExternalIds(tmdb = 603),
                    catalog = TrackingCatalogReference(contentId = "tmdb:603", contentType = "movie", videoId = "tmdb:603"),
                ),
            )
            dispatchTrackingScrobble(listOf(trakt), 1, TrackingScrobbleAction.START, matched)
            assertEquals(1, trakt.callCount, "a TMDB title played from a server is a normal scrobble")
        } finally {
            com.nuvio.app.core.contracts.OwnSourcePolicy.resetForTest()
        }
    }

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        override val seekScrobblePolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE,
        private val failure: Throwable? = null,
    ) : TrackingScrobbler {
        var callCount: Int = 0

        override suspend fun scrobble(
            profileId: Int,
            action: TrackingScrobbleAction,
            event: TrackingScrobbleEvent,
        ) {
            callCount += 1
            failure?.let { throw it }
        }
    }
}
