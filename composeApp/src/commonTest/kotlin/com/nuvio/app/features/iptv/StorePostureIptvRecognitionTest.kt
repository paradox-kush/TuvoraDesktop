package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.addons.AddonSourcePolicy
import com.nuvio.app.features.iptv.match.XtreamStreamSource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Store builds play only IPTV, never add-on streams ([AddonSourcePolicy]). IPTV is recognised by the
 * stream's addonId ("xtream" / "xtream-match:<accountId>"), never by URL host or content id, so it must
 * keep working for every Step 0.2 playlist-key shape (the account id) and for stream URLs that Step 0.3
 * failover rebased onto a backup host.
 */
class StorePostureIptvRecognitionTest {

    @BeforeTest
    fun wire() {
        resetAllSourceRegistriesForTest()
        IptvSourceRegistrations.register()
    }

    @AfterTest
    fun unwire() = resetAllSourceRegistriesForTest()

    private val playlistKeys: List<String> = listOfNotNull(
        PlaylistKey.xtream("http://Panel.Example.com:80/player_api.php", "alice"),
        PlaylistKey.m3uUrl("panel.example.com/get.php?username=a&password=b&type=m3u_plus"),
        PlaylistKey.stalker("http://portal.example.com:8080/c/", "00:1a:79:aa:bb:cc"),
        PlaylistKey.m3uFile("my list.m3u", 1_727_740_800_000L),
    ).let { keys -> keys + keys.map { "$it#2" } }

    private fun account(key: String) = XtreamAccount(
        id = key,
        name = "My IPTV",
        // A backup host: Step 0.3 builds and rebases stream URLs onto whichever server is active.
        baseUrl = "http://backup.example.net:2095",
        username = "alice",
        password = "secret",
    )

    @Test
    fun `every playlist key shape is built`() {
        assertEquals(8, playlistKeys.size)
    }

    @Test
    fun `store builds keep matched-lane IPTV streams and cached links for every playlist key shape`() {
        for (key in playlistKeys) {
            val groupId = XtreamStreamSource.groupId(account(key))
            val matched = StreamItem(
                name = "1080p",
                url = "http://backup.example.net:2095/movie/alice/secret/42.mkv",
                addonName = "My IPTV",
                addonId = groupId,
            )

            assertTrue(StreamLinkCacheRepository.isIptvAddon(groupId), "matched lane is IPTV for key $key")
            assertTrue(
                AddonSourcePolicy.cachedLinkUsable(groupId, false, StreamLinkCacheRepository::isIptvAddon),
                "store build keeps the cached IPTV link for key $key",
            )
            assertEquals(
                listOf(matched),
                AddonSourcePolicy.embeddedStreamsForBuild(listOf(matched), false, StreamLinkCacheRepository::isIptvAddon),
                "store build keeps the IPTV stream for key $key",
            )
        }
    }

    @Test
    fun `store builds keep rebased IPTV episodes and still drop add-on streams`() {
        val episode = StreamItem(
            name = "Direct",
            url = "http://backup.example.net:2095/series/alice/secret/7.mkv",
            addonName = "My IPTV",
            addonId = "xtream",
        )
        // An add-on stream on the same host as the IPTV backup must still be dropped: hosts never decide.
        val addon = StreamItem(
            name = "1080p",
            url = "http://backup.example.net:2095/movie/alice/secret/42.mkv",
            addonName = "Some addon",
            addonId = "embedded",
        )

        assertEquals(
            listOf(episode),
            AddonSourcePolicy.embeddedStreamsForBuild(listOf(episode, addon), false, StreamLinkCacheRepository::isIptvAddon),
        )
        assertFalse(AddonSourcePolicy.cachedLinkUsable("embedded", false, StreamLinkCacheRepository::isIptvAddon))
    }
}
