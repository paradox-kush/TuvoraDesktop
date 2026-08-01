package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StreamLinkCacheRepositoryTest {

    /**
     * IPTV links must never be cached: a panel can renumber its whole VOD catalog (seen live —
     * "+6249 -6253" in one sync), which strands the cached stream id and makes the replayed URL
     * 401. Stalker links are single-use on top of that. Addon/debrid links keep caching.
     */
    @Test
    fun `iptv addon ids are recognised so their links are never cached`() {
        assertTrue(StreamLinkCacheRepository.isIptvAddon("xtream"))
        assertTrue(StreamLinkCacheRepository.isIptvAddon("xtream-match:http://panel.example|user"))
        assertTrue(
            StreamLinkCacheRepository.isIptvAddon(
                "xtream-match:stalker|http://portal.example|00:1A:79:00:00:01",
            ),
        )
    }

    @Test
    fun `addon and debrid ids stay cacheable`() {
        assertFalse(StreamLinkCacheRepository.isIptvAddon("com.stremio.torrentio.addon"))
        assertFalse(StreamLinkCacheRepository.isIptvAddon("embedded"))
        assertFalse(StreamLinkCacheRepository.isIptvAddon(null))
        assertFalse(StreamLinkCacheRepository.isIptvAddon(""))
        // Not a prefix match on a foreign id that merely mentions the word.
        assertFalse(StreamLinkCacheRepository.isIptvAddon("my-xtream-mirror"))
    }

    @Test
    fun `movie cache key keeps legacy type and video id shape`() {
        val key = StreamLinkCacheRepository.contentKey(
            type = "movie",
            videoId = "tt123",
        )

        assertEquals("movie|tt123", key)
    }

    @Test
    fun `episode cache key is scoped to parent show and episode`() {
        val firstEpisode = StreamLinkCacheRepository.contentKey(
            type = "series",
            videoId = "video-id",
            parentMetaId = "tt999",
            season = 1,
            episode = 1,
        )
        val secondEpisode = StreamLinkCacheRepository.contentKey(
            type = "series",
            videoId = "video-id",
            parentMetaId = "tt999",
            season = 1,
            episode = 2,
        )

        assertNotEquals(firstEpisode, secondEpisode)
        assertEquals("series|tt999|s1|e1|video-id", firstEpisode)
    }
}
