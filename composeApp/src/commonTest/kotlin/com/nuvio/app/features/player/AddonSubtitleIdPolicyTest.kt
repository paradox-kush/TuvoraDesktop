package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonSubtitleIdPolicyTest {

    private val m3uEpisode = "xtream:m3u|http://panel.example/get.php?username=u&password=p:episode:991"

    @Test
    fun providerIdsAreScopedAndNeverRequested() {
        assertTrue(AddonSubtitleIdPolicy.isProviderScoped(m3uEpisode))
        assertFalse(AddonSubtitleIdPolicy.isProviderScoped("tt0388629:1:2"))
        assertNull(AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, resolvedPublicId = null))
        assertEquals("tt0388629:1:2", AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, "tt0388629:1:2"))
        assertEquals("tt0111161", AddonSubtitleIdPolicy.requestVideoId("tt0111161", resolvedPublicId = null))
    }

    @Test
    fun publicIdsFollowTheStremioConvention() {
        assertEquals("tt0111161", AddonSubtitleIdPolicy.publicVideoId("tt0111161", isSeries = false, null, null))
        assertEquals("tt0388629:3:12", AddonSubtitleIdPolicy.publicVideoId("TT0388629", isSeries = true, 3, 12))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("tt0388629", isSeries = true, 3, null))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("1111", isSeries = false, null, null))
        assertNull(AddonSubtitleIdPolicy.publicVideoId(null, isSeries = false, null, null))
        assertEquals("series", AddonSubtitleIdPolicy.requestType("movie", "tt0388629:3:12"))
        assertEquals("movie", AddonSubtitleIdPolicy.requestType("movie", "tt0111161"))
    }

    @Test
    fun anAddonWithoutIdPrefixesIsNeverGivenAProviderId() {
        // Privacy regression: an add-on declaring no idPrefixes matched every id, so the fetch key
        // (and then the request URL) carried the playlist credentials embedded in the IPTV id.
        val openAddon = ManagedAddon(
            manifestUrl = "https://subs.example/manifest.json",
            manifest = AddonManifest(
                id = "open.subs",
                name = "Open subs",
                description = "",
                version = "1.0.0",
                transportUrl = "https://subs.example",
                resources = listOf(AddonResource(name = "subtitles", types = emptyList(), idPrefixes = emptyList())),
                types = emptyList(),
            ),
        )
        assertNull(buildAddonSubtitleFetchKey(listOf(openAddon), "movie", m3uEpisode))
    }

    @Test
    fun anAddonSubtitleRequestCarriesOnlyTheTypeAndPublicId() {
        // T5 parity pin: NuvioTV once appended `filename=<stream file>` (the provider's stream id or a
        // token) to the add-on request. Mobile/Desktop/Apple TV build the URL from type + id alone;
        // a filename extra must never be added for an IPTV item.
        val url = com.nuvio.app.features.addons.buildAddonResourceUrl(
            manifestUrl = "https://subs.example/manifest.json",
            resource = "subtitles",
            type = "movie",
            id = "tt0133093",
        )
        assertEquals("https://subs.example/subtitles/movie/tt0133093.json", url)
    }
}
