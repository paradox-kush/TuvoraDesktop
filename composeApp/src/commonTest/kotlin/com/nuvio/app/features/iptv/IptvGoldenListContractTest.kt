package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvContentClassifierAccess
import com.nuvio.app.core.contracts.MetaSourceAccess
import com.nuvio.app.core.contracts.StreamSourceAccess
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.addons.AddonSourcePolicy
import com.nuvio.app.features.streams.PlaybackAvailability
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GOLDEN-LIST CONTRACT for Wave 3 / P0 (media-servers design 5.1): making sources plural must leave
 * IPTV behaviour byte-identical. The tables below were recorded from the code BEFORE the P0
 * refactor (hard-coded "xtream:" / "xtream" / "xtream-match:" predicates and single-slot ports) and
 * must never change in a P0 commit - only the registration lines in [wire]/[unwire] may. If a row here
 * has to change, IPTV behaviour changed and that needs its own reviewed decision.
 *
 * Every input goes through the same facades production call sites use (PlaybackAvailability,
 * StreamLinkCacheRepository, AddonSourcePolicy, the *Access ports), with IPTV registered the way
 * FeatureContributions registers it.
 */
class IptvGoldenListContractTest {

    @BeforeTest
    fun wire() {
        resetAllSourceRegistriesForTest()
        IptvSourceRegistrations.register()
    }

    @AfterTest
    fun unwire() = resetAllSourceRegistriesForTest()

    private val contentIds: List<String?> = listOf(
        "xtream:acc1:vod:101",
        "xtream:acc1:live:55",
        "xtream:acc1:series:9",
        "xtream:stalker|http://p.example:80/c/|00:1a:79:aa:bb:cc:live:7",
        "xtream:",
        "xtream",
        "xtreamx:acc:vod:1",
        "XTREAM:acc:vod:1",
        " xtream:a:vod:1",
        "m3u:abc",
        "tt0111161",
        "tmdb:603",
        "kitsu:1:2",
        "",
        null,
    )

    private val providerIds: List<String?> = listOf(
        "xtream",
        "xtream-match:acc1",
        "xtream-match:",
        "xtream-match:http://panel.example|user",
        "xtream-match:stalker|http://portal.example|00:1A:79:00:00:01",
        "xtream:acc1",
        "Xtream",
        "my-xtream-mirror",
        "com.stremio.torrentio.addon",
        "embedded",
        "ms",
        "ms-match:jellyfin:abc:u1",
        "",
        null,
    )

    private val deferredUrls: List<String?> = listOf(
        "stalker-deferred:acc1|movie|12|Heat",
        "stalker-deferred:stalker|http://p.example|00:1a:79:aa:bb:cc|episode|3|1|2",
        "stalker-deferred",
        "http://panel.example/movie/u/p/1.mp4",
        "",
        null,
    )

    private fun Boolean.flag() = if (this) "T" else "F"

    private fun contentRow(id: String?): String {
        val classifier = IptvContentClassifierAccess.classifier
        val streams = StreamSourceAccess.current()
        val meta = MetaSourceAccess.current()
        val prefix = id?.let { PlaybackAvailability.isIptvId(it) } ?: false
        val xtream = id?.let { classifier.isXtreamId(it) } ?: false
        val live = id?.let { classifier.isLiveId(it) } ?: false
        val handled = streams.isHandledId(id)
        val handlesMeta = id?.let { meta.handlesId(it) } ?: false
        return "${id ?: "<null>"} -> prefix=${prefix.flag()} xtream=${xtream.flag()} live=${live.flag()} " +
            "streamHandled=${handled.flag()} metaHandles=${handlesMeta.flag()}"
    }

    private fun providerRow(addonId: String?): String {
        val classifier = IptvContentClassifierAccess.classifier
        val streams = StreamSourceAccess.current()
        val own = StreamLinkCacheRepository.isIptvAddon(addonId)
        val group = addonId?.let { classifier.isXtreamStreamGroup(it) } ?: false
        val match = addonId?.let { streams.isMatchSourceId(it) } ?: false
        val storeUsable = AddonSourcePolicy.cachedLinkUsable(addonId, false, StreamLinkCacheRepository::isIptvAddon)
        val fullUsable = AddonSourcePolicy.cachedLinkUsable(addonId, true, StreamLinkCacheRepository::isIptvAddon)
        val item = StreamItem(addonName = "A", addonId = addonId.orEmpty())
        val storeKept = AddonSourcePolicy
            .embeddedStreamsForBuild(listOf(item), false, StreamLinkCacheRepository::isIptvAddon).isNotEmpty()
        return "${addonId ?: "<null>"} -> own=${own.flag()} group=${group.flag()} match=${match.flag()} " +
            "cacheUsableStore=${storeUsable.flag()} cacheUsableFull=${fullUsable.flag()} embeddedKeptStore=${storeKept.flag()}"
    }

    private fun deferredRow(url: String?): String =
        "${url ?: "<null>"} -> deferred=${StreamSourceAccess.current().isDeferredUrl(url).flag()}"

    @Test
    fun `content id recognition is frozen`() {
        val expected = """
            |xtream:acc1:vod:101 -> prefix=T xtream=T live=F streamHandled=T metaHandles=T
            |xtream:acc1:live:55 -> prefix=T xtream=T live=T streamHandled=T metaHandles=T
            |xtream:acc1:series:9 -> prefix=T xtream=T live=F streamHandled=T metaHandles=T
            |xtream:stalker|http://p.example:80/c/|00:1a:79:aa:bb:cc:live:7 -> prefix=T xtream=T live=T streamHandled=T metaHandles=T
            |xtream: -> prefix=T xtream=T live=F streamHandled=T metaHandles=T
            |xtream -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |xtreamx:acc:vod:1 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |XTREAM:acc:vod:1 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            | xtream:a:vod:1 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |m3u:abc -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |tt0111161 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |tmdb:603 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |kitsu:1:2 -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            | -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
            |<null> -> prefix=F xtream=F live=F streamHandled=F metaHandles=F
        """.trimMargin()
        assertEquals(expected, contentIds.joinToString("\n", transform = ::contentRow), "content-id golden list")
    }

    @Test
    fun `provider id recognition is frozen`() {
        val expected = """
            |xtream -> own=T group=F match=F cacheUsableStore=T cacheUsableFull=T embeddedKeptStore=T
            |xtream-match:acc1 -> own=T group=T match=T cacheUsableStore=T cacheUsableFull=T embeddedKeptStore=T
            |xtream-match: -> own=T group=T match=T cacheUsableStore=T cacheUsableFull=T embeddedKeptStore=T
            |xtream-match:http://panel.example|user -> own=T group=T match=T cacheUsableStore=T cacheUsableFull=T embeddedKeptStore=T
            |xtream-match:stalker|http://portal.example|00:1A:79:00:00:01 -> own=T group=T match=T cacheUsableStore=T cacheUsableFull=T embeddedKeptStore=T
            |xtream:acc1 -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |Xtream -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |my-xtream-mirror -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |com.stremio.torrentio.addon -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |embedded -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |ms -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |ms-match:jellyfin:abc:u1 -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            | -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
            |<null> -> own=F group=F match=F cacheUsableStore=F cacheUsableFull=T embeddedKeptStore=F
        """.trimMargin()
        assertEquals(expected, providerIds.joinToString("\n", transform = ::providerRow), "provider-id golden list")
    }

    @Test
    fun `deferred url recognition is frozen`() {
        val expected = """
            |stalker-deferred:acc1|movie|12|Heat -> deferred=T
            |stalker-deferred:stalker|http://p.example|00:1a:79:aa:bb:cc|episode|3|1|2 -> deferred=T
            |stalker-deferred -> deferred=F
            |http://panel.example/movie/u/p/1.mp4 -> deferred=F
            | -> deferred=F
            |<null> -> deferred=F
        """.trimMargin()
        assertEquals(expected, deferredUrls.joinToString("\n", transform = ::deferredRow), "deferred-url golden list")
    }

    @Test
    fun `the direct lane group id is the xtream literal for every iptv direct item`() {
        // The lane groups its single stream under this id (feeds StreamLinkCacheRepository.save and
        // PlayerLaunch.providerAddonId), so the id the resolver stamps on the item IS the group id.
        val vod = XtreamResolvedItem("xtream:acc1:vod:101", "acc1", XtreamKind.VOD, "Heat", "http://p.example/movie/u/p/101.mp4")
        val live = XtreamResolvedItem("xtream:acc1:live:55", "acc1", XtreamKind.LIVE, "News", "http://p.example/live/u/p/55.ts", streamType = "live")
        assertEquals("xtream", vod.toStreamItem("My IPTV")?.addonId)
        assertEquals("xtream", live.toStreamItem("My IPTV")?.addonId)
        assertEquals("My IPTV", vod.toStreamItem("My IPTV")?.addonName)
    }
}
