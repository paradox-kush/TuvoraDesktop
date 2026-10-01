package com.nuvio.app.features.addons

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.supportsStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddonSourcePolicyTest {
    private val everything = AddonManifest(
        id = "org.example.all",
        name = "All-in-one",
        description = "",
        version = "1.0.0",
        resources = listOf(
            AddonResource(name = "catalog", types = listOf("movie")),
            AddonResource(name = "meta", types = listOf("movie")),
            AddonResource(name = "stream", types = listOf("movie"), idPrefixes = listOf("tt")),
            AddonResource(name = "subtitles", types = listOf("movie")),
        ),
        types = listOf("movie"),
        transportUrl = "https://example.org/manifest.json",
    )

    private val isIptv: (String?) -> Boolean = { it == "xtream" }

    @Test
    fun `store builds keep discovery resources and drop the stream resource`() {
        val store = AddonSourcePolicy.manifestForBuild(everything, streamSourcesEnabled = false)

        assertEquals(listOf("catalog", "meta", "subtitles"), store.resources.map { it.name })
        assertFalse(store.supportsStream(type = "movie", videoId = "tt0111161"))
        assertEquals(everything.catalogs, store.catalogs)
    }

    @Test
    fun `full builds keep the manifest untouched`() {
        val full = AddonSourcePolicy.manifestForBuild(everything, streamSourcesEnabled = true)

        assertSame(everything, full)
        assertTrue(full.supportsStream(type = "movie", videoId = "tt0111161"))
    }

    @Test
    fun `store builds keep IPTV embedded streams and drop addon ones`() {
        val iptvEpisode = StreamItem(name = "Direct", url = "http://iptv/series/1.mkv", addonName = "My IPTV", addonId = "xtream")
        val addonStream = StreamItem(name = "1080p", url = "http://addon/stream.mkv", addonName = "Some addon", addonId = "addon:some")

        val store = AddonSourcePolicy.embeddedStreamsForBuild(listOf(iptvEpisode, addonStream), false, isIptv)
        val full = AddonSourcePolicy.embeddedStreamsForBuild(listOf(iptvEpisode, addonStream), true, isIptv)

        assertEquals(listOf(iptvEpisode), store)
        assertEquals(listOf(iptvEpisode, addonStream), full)
    }

    @Test
    fun `store builds never replay a cached addon link`() {
        assertFalse(AddonSourcePolicy.cachedLinkUsable("addon:some", streamSourcesEnabled = false, isIptv = isIptv))
        assertTrue(AddonSourcePolicy.cachedLinkUsable("xtream", streamSourcesEnabled = false, isIptv = isIptv))
        assertTrue(AddonSourcePolicy.cachedLinkUsable("addon:some", streamSourcesEnabled = true, isIptv = isIptv))
    }
}
