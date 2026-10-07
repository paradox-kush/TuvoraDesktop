package com.nuvio.app.core.contracts

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wave 3 / P0 contract tests, one block per plural source port (media-servers design 5.1): duplicate
 * registration is refused, nothing registered is a clean no-op, one registered provider answers exactly
 * as itself, and several route by who owns the id.
 */
class SourceRegistriesContractTest {

    @BeforeTest
    fun clean() = resetAllSourceRegistriesForTest()

    @AfterTest
    fun restore() = resetAllSourceRegistriesForTest()

    // ---- fakes -----------------------------------------------------------------------------

    /** A source that owns ids with [prefix] and match groups / provider ids with "[prefix]-match:". */
    private class FakeStreams(private val prefix: String, private val groups: List<String> = emptyList()) : StreamSourceProvider {
        val minted = mutableListOf<String>()
        override fun isHandledId(videoId: String?) = videoId != null && videoId.startsWith("$prefix:")
        override fun isStalkerSource(videoId: String) = prefix == "stalkerish"
        override fun directStreamItem(videoId: String): StreamItem? =
            if (isHandledId(videoId)) StreamItem(addonName = prefix, addonId = prefix, url = "https://$prefix/$videoId") else null
        override fun matchSourceGroups(type: String) = groups.map { StreamSourceGroup(it, "$prefix-$type") }
        override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?) =
            listOf(StreamItem(addonName = prefix, addonId = sourceId, url = "https://$prefix/$sourceId/$videoId"))
        override fun isMatchSourceId(providerAddonId: String) = providerAddonId.startsWith("$prefix-match:")
        override fun isDeferredUrl(url: String?) = url != null && url.startsWith("$prefix-deferred:")
        override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? {
            minted += url
            return "https://$prefix/minted/${if (forceMint) "forced" else "plain"}"
        }
    }

    private class FakeMeta(private val prefix: String) : MetaSourceProvider {
        val ensured = mutableListOf<String>()
        override fun handlesId(id: String) = id.startsWith("$prefix:")
        override suspend fun buildNativeMeta(id: String): MetaDetails? = null
        override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean {
            ensured += "$id/$forceFresh/$forceMint"
            return true
        }
    }

    private class FakeClassifier(private val prefix: String, private val poster: String? = null) : IptvContentClassifier {
        override fun isLiveId(id: String) = id.startsWith("$prefix:") && id.contains(":live:")
        override fun isOrphaned(id: String) = id.startsWith("$prefix:") && id.endsWith(":gone")
        override fun isXtreamId(id: String) = id.startsWith("$prefix:")
        override fun posterFor(id: String) = if (id.startsWith("$prefix:")) poster else null
        override fun isXtreamStreamGroup(addonId: String) = addonId.startsWith("$prefix-match:")
    }

    private class FakeSearch(
        var enabled: Boolean = true,
        private val rows: List<HomeCatalogSection> = emptyList(),
        private val failing: Boolean = false,
        signature: String? = null,
    ) : IptvSearchProvider {
        val sig = MutableStateFlow(signature)
        override fun isEnabled() = enabled
        override suspend fun search(query: String): List<HomeCatalogSection> {
            if (failing) error("boom")
            return rows
        }
        override fun sourceSignature(): String? = sig.value
        override fun sourceSignatureChanges(): Flow<String?> = sig
    }

    private fun row(key: String) = HomeCatalogSection(
        key = key, title = key, subtitle = "", addonName = "", target = CatalogTarget.Library("movie", key), items = emptyList(),
    )

    // ---- duplicate refusal -----------------------------------------------------------------

    @Test
    fun `every registry refuses a duplicate name and keeps the first entry`() {
        val first = FakeStreams("a")
        StreamSourceRegistry.register("iptv", first)
        val dup = assertFailsWith<IllegalArgumentException> { StreamSourceRegistry.register("iptv", FakeStreams("b")) }
        assertTrue("iptv" in dup.message.orEmpty(), "the message names the duplicate")
        assertEquals(listOf<StreamSourceProvider>(first), StreamSourceRegistry.all, "a refused registration changes nothing")

        MetaSourceRegistry.register("iptv", FakeMeta("a"))
        assertFailsWith<IllegalArgumentException> { MetaSourceRegistry.register("iptv", FakeMeta("b")) }

        SearchProviderRegistry.register("iptv", FakeSearch())
        assertFailsWith<IllegalArgumentException> { SearchProviderRegistry.register("iptv", FakeSearch()) }

        ContentClassifierRegistry.register("iptv", FakeClassifier("a"))
        assertFailsWith<IllegalArgumentException> { ContentClassifierRegistry.register("iptv", FakeClassifier("b")) }

        OwnSourcePolicy.registerContentIdPredicate("iptv") { true }
        assertFailsWith<IllegalArgumentException> { OwnSourcePolicy.registerContentIdPredicate("iptv") { false } }
        OwnSourcePolicy.registerProviderIdPredicate("iptv") { true }
        assertFailsWith<IllegalArgumentException> { OwnSourcePolicy.registerProviderIdPredicate("iptv") { false } }
    }

    @Test
    fun `the same name may live in different registries`() {
        StreamSourceRegistry.register("iptv", FakeStreams("a"))
        MetaSourceRegistry.register("iptv", FakeMeta("a"))
        assertEquals(1, StreamSourceRegistry.all.size)
        assertEquals(1, MetaSourceRegistry.all.size)
    }

    @Test
    fun `a blank name is refused`() {
        assertFailsWith<IllegalArgumentException> { StreamSourceRegistry.register(" ", FakeStreams("a")) }
    }

    // ---- stream sources --------------------------------------------------------------------

    @Test
    fun `with nothing registered the stream port is a clean no-op`() = runTest {
        val streams = StreamSourceAccess.current()
        assertFalse(streams.isHandledId("xtream:a:vod:1"))
        assertFalse(streams.isStalkerSource("xtream:a:vod:1"))
        assertNull(streams.directStreamItem("xtream:a:vod:1"))
        assertEquals(emptyList(), streams.matchSourceGroups("movie"))
        assertEquals(emptyList(), streams.resolveMatchStreams("x", "movie", "tt1", null, null))
        assertFalse(streams.isMatchSourceId("xtream-match:a"))
        assertFalse(streams.isDeferredUrl("stalker-deferred:a"))
        assertNull(streams.resolveDeferredUrl("stalker-deferred:a", false))
    }

    @Test
    fun `the access facade hands out one stable combined provider`() {
        assertSame(StreamSourceAccess.current(), StreamSourceAccess.current())
        assertSame(MetaSourceAccess.current(), MetaSourceAccess.current())
    }

    @Test
    fun `a single registered stream source answers exactly as itself`() = runTest {
        val only = FakeStreams("xtream", groups = listOf("xtream-match:acc1", "xtream-match:acc2"))
        StreamSourceRegistry.register("iptv", only)
        val streams = StreamSourceAccess.current()

        assertTrue(streams.isHandledId("xtream:a:vod:1"))
        assertFalse(streams.isHandledId("tt1"))
        assertEquals(only.directStreamItem("xtream:a:vod:1"), streams.directStreamItem("xtream:a:vod:1"))
        assertEquals(only.matchSourceGroups("movie"), streams.matchSourceGroups("movie"))
        assertEquals(
            only.resolveMatchStreams("xtream-match:acc1", "movie", "tt1", null, null),
            streams.resolveMatchStreams("xtream-match:acc1", "movie", "tt1", null, null),
        )
        assertTrue(streams.isDeferredUrl("xtream-deferred:1"))
        assertEquals("https://xtream/minted/forced", streams.resolveDeferredUrl("xtream-deferred:1", true))
    }

    @Test
    fun `several stream sources route by who owns the id`() = runTest {
        val iptv = FakeStreams("xtream", groups = listOf("xtream-match:acc1"))
        val server = FakeStreams("ms", groups = listOf("ms-match:srv1", "ms-match:srv2"))
        StreamSourceRegistry.register("iptv", iptv)
        StreamSourceRegistry.register("mediaserver", server)
        val streams = StreamSourceAccess.current()

        assertTrue(streams.isHandledId("xtream:a:vod:1"))
        assertTrue(streams.isHandledId("ms:jellyfin:m:u:movie:9"))
        assertFalse(streams.isHandledId("tt1"))
        assertEquals("ms", streams.directStreamItem("ms:jellyfin:m:u:movie:9")?.addonId)
        assertEquals("xtream", streams.directStreamItem("xtream:a:vod:1")?.addonId)
        assertNull(streams.directStreamItem("tt1"))

        assertEquals(
            listOf("xtream-match:acc1", "ms-match:srv1", "ms-match:srv2"),
            streams.matchSourceGroups("movie").map { it.sourceId },
            "groups concatenate in registration order",
        )
        assertTrue(streams.isMatchSourceId("ms-match:srv1"))
        assertFalse(streams.isMatchSourceId("other:srv1"))
        assertEquals(
            "https://ms/ms-match:srv2/tt9",
            streams.resolveMatchStreams("ms-match:srv2", "movie", "tt9", null, null).single().url,
            "a group resolves through its owner",
        )
        assertEquals(emptyList(), streams.resolveMatchStreams("nobody-match:x", "movie", "tt9", null, null))

        assertTrue(streams.isDeferredUrl("ms-deferred:1"))
        assertEquals("https://ms/minted/plain", streams.resolveDeferredUrl("ms-deferred:1", false))
        assertEquals(emptyList(), iptv.minted, "the other source is never asked to mint")
        assertEquals(listOf("ms-deferred:1"), server.minted)
    }

    @Test
    fun `stalker status follows the owner of the id`() {
        StreamSourceRegistry.register("iptv", FakeStreams("stalkerish"))
        StreamSourceRegistry.register("mediaserver", FakeStreams("ms"))
        val streams = StreamSourceAccess.current()
        assertTrue(streams.isStalkerSource("stalkerish:x"))
        assertFalse(streams.isStalkerSource("ms:x"))
        assertFalse(streams.isStalkerSource("tt1"))
    }

    // ---- meta sources ----------------------------------------------------------------------

    @Test
    fun `meta sources route to the owner and a miss is a no-op`() = runTest {
        val iptv = FakeMeta("xtream")
        val server = FakeMeta("ms")
        val meta = MetaSourceAccess.current()
        assertFalse(meta.handlesId("xtream:a:vod:1"))
        assertFalse(meta.ensureStreamRegistered("xtream:a:vod:1", false, false))

        MetaSourceRegistry.register("iptv", iptv)
        MetaSourceRegistry.register("mediaserver", server)
        assertTrue(meta.handlesId("ms:x"))
        assertFalse(meta.handlesId("tt1"))
        assertTrue(meta.ensureStreamRegistered("ms:x", true, false))
        assertEquals(listOf("ms:x/true/false"), server.ensured)
        assertEquals(emptyList(), iptv.ensured)
        assertFalse(meta.ensureStreamRegistered("tt1", false, false), "an id nobody owns is not registered")
    }

    // ---- content classifier ----------------------------------------------------------------

    @Test
    fun `the composite classifier is any-of and posters are first-found`() {
        val classifier = IptvContentClassifierAccess.classifier
        assertFalse(classifier.isXtreamId("xtream:a:vod:1"), "nothing registered: nothing is own content")
        assertNull(classifier.posterFor("xtream:a:vod:1"))

        ContentClassifierRegistry.register("iptv", FakeClassifier("xtream", poster = "https://img/x"))
        ContentClassifierRegistry.register("mediaserver", FakeClassifier("ms", poster = "https://img/ms"))
        assertTrue(classifier.isLiveId("xtream:a:live:5"))
        assertFalse(classifier.isLiveId("ms:a:movie:5"))
        assertTrue(classifier.isOrphaned("ms:a:gone"), "a removed server clears its cards")
        assertFalse(classifier.isOrphaned("xtream:a:vod:1"))
        assertTrue(classifier.isXtreamId("xtream:a"))
        assertTrue(classifier.isXtreamStreamGroup("ms-match:srv"))
        assertEquals("https://img/ms", classifier.posterFor("ms:a:movie:5"))
        assertEquals("https://img/x", classifier.posterFor("xtream:a:vod:1"))
        assertNull(classifier.posterFor("tt1"))
    }

    // ---- search ----------------------------------------------------------------------------

    @Test
    fun `search with nothing registered has no provider`() {
        assertNull(IptvSearchAccess.providerOrNull)
        assertFailsWith<IllegalStateException> { IptvSearchAccess.provider }
    }

    @Test
    fun `a single search provider is passed through unchanged`() = runTest {
        val only = FakeSearch(rows = listOf(row("a")), signature = "sig-1")
        SearchProviderRegistry.register("iptv", only)
        val provider = IptvSearchAccess.provider

        assertTrue(provider.isEnabled())
        assertEquals(listOf("a"), provider.search("q").map { it.key })
        assertEquals("sig-1", provider.sourceSignature())
        assertSame(only.sig, provider.sourceSignatureChanges(), "the sole provider's own flow, not a wrapper")
    }

    @Test
    fun `a disabled search provider is not asked and adds no signature`() = runTest {
        SearchProviderRegistry.register("iptv", FakeSearch(enabled = false, rows = listOf(row("hidden")), signature = null))
        val provider = IptvSearchAccess.provider
        assertFalse(provider.isEnabled())
        assertEquals(emptyList(), provider.search("q"))
        assertNull(provider.sourceSignature())
    }

    @Test
    fun `several search providers merge their rows in registration order and survive a failing one`() = runTest {
        SearchProviderRegistry.register("iptv", FakeSearch(rows = listOf(row("iptv-row")), signature = "A"))
        SearchProviderRegistry.register("broken", FakeSearch(failing = true, signature = "B"))
        SearchProviderRegistry.register("off", FakeSearch(enabled = false, rows = listOf(row("off-row"))))
        SearchProviderRegistry.register("mediaserver", FakeSearch(rows = listOf(row("srv-row")), signature = "C"))
        val provider = IptvSearchAccess.provider

        assertEquals(listOf("iptv-row", "srv-row"), provider.search("q").map { it.key })
        assertEquals("A|B|C", provider.sourceSignature(), "disabled providers carry a null signature")
        assertTrue(provider.isEnabled())
    }

    @Test
    fun `the combined signature flow re-emits when any provider changes and only then`() = runTest {
        val iptv = FakeSearch(signature = "A")
        val server = FakeSearch(signature = null)
        SearchProviderRegistry.register("iptv", iptv)
        SearchProviderRegistry.register("mediaserver", server)
        val flow = IptvSearchAccess.provider.sourceSignatureChanges()

        assertEquals("A", flow.first())
        server.sig.value = "S1"
        assertEquals("A|S1", flow.first())
        iptv.sig.value = null
        assertEquals("S1", flow.first())
        server.sig.value = null
        assertNull(flow.first(), "no enabled source anywhere: no signature")
    }

    // ---- own-source policy -----------------------------------------------------------------

    @Test
    fun `own source policy keeps content ids and provider ids apart`() {
        assertFalse(OwnSourcePolicy.isOwnContentId("xtream:a:vod:1"), "nothing registered: nothing is own")
        assertFalse(OwnSourcePolicy.isOwnProviderId("xtream"))

        OwnSourcePolicy.registerContentIdPredicate("iptv") { it.startsWith("xtream:") }
        OwnSourcePolicy.registerProviderIdPredicate("iptv") { it == "xtream" || it.startsWith("xtream-match:") }
        OwnSourcePolicy.registerContentIdPredicate("mediaserver") { it.startsWith("ms:") }
        OwnSourcePolicy.registerProviderIdPredicate("mediaserver") { it == "ms" || it.startsWith("ms-match:") }

        assertTrue(OwnSourcePolicy.isOwnContentId("xtream:a:vod:1"))
        assertTrue(OwnSourcePolicy.isOwnContentId("ms:jellyfin:m:u:movie:9"))
        assertFalse(OwnSourcePolicy.isOwnContentId("tt0111161"))
        assertFalse(OwnSourcePolicy.isOwnContentId("xtream"), "a provider id is not a content id")
        assertFalse(OwnSourcePolicy.isOwnContentId(null))

        assertTrue(OwnSourcePolicy.isOwnProviderId("xtream"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("xtream-match:acc"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("ms"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("ms-match:jellyfin:m"))
        assertFalse(OwnSourcePolicy.isOwnProviderId("ms:jellyfin:m:u:movie:9"), "a content id is not a provider id")
        assertFalse(OwnSourcePolicy.isOwnProviderId("com.stremio.torrentio.addon"))
        assertFalse(OwnSourcePolicy.isOwnProviderId(null))
        assertFalse(OwnSourcePolicy.isOwnProviderId(""))
        assertFalse(OwnSourcePolicy.isOwnProviderId("  "))
    }

    // ---- home contributors + session reporters (registries) --------------------------------

    private class FakeContributor(
        override val name: String,
        private val rows: List<HomeCatalogSection> = emptyList(),
        private val owns: String = name,
        private val fail: Boolean = false,
    ) : HomeSectionContributor {
        override suspend fun sections(forceRefresh: Boolean): List<HomeCatalogSection> {
            if (fail) throw IllegalStateException("offline")
            return rows
        }
        override fun ownsSource(sourceKey: String) = sourceKey == owns
        override suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?) =
            CatalogPage(items = emptyList(), rawItemCount = skip ?: 0, nextSkip = null)
    }

    @Test
    fun `home contributors refuse duplicates isolate failures and dedupe keys`() = runTest {
        assertTrue(HomeSectionContributorRegistry.isEmpty)
        HomeSectionContributorRegistry.register(FakeContributor("a", rows = listOf(row("k1"), row("k2"))))
        assertFailsWith<IllegalArgumentException> { HomeSectionContributorRegistry.register(FakeContributor("a")) }
        HomeSectionContributorRegistry.register(FakeContributor("broken", fail = true))
        HomeSectionContributorRegistry.register(FakeContributor("b", rows = listOf(row("k2"), row("k3"))))

        assertEquals(
            listOf("k1", "k2", "k3"),
            HomeSectionContributorRegistry.collectSections(forceRefresh = false).map { it.key },
            "a failing contributor adds nothing; a repeated key keeps the first row",
        )
    }

    @Test
    fun `a see-all page is served by the contributor that owns the source key`() = runTest {
        HomeSectionContributorRegistry.register(FakeContributor("jellyfin:m1", owns = "jellyfin:m1"))
        HomeSectionContributorRegistry.register(FakeContributor("emby:m2", owns = "emby:m2"))

        val page = HomeSectionContributorRegistry.loadSourcePage(CatalogTarget.Source("emby:m2", "latest", "movie"), skip = 40)
        assertEquals(40, page?.rawItemCount, "served by the contributor that owns emby:m2")
        assertNull(HomeSectionContributorRegistry.loadSourcePage(CatalogTarget.Source("plex:x", "latest", "movie"), skip = null))
    }

    @Test
    fun `cancellation is not swallowed by contributor isolation`() = runTest {
        HomeSectionContributorRegistry.register(object : HomeSectionContributor {
            override val name = "cancelled"
            override suspend fun sections(forceRefresh: Boolean): List<HomeCatalogSection> = throw CancellationException("stop")
            override fun ownsSource(sourceKey: String) = false
            override suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?) = CatalogPage(emptyList(), 0, null)
        })
        assertFailsWith<CancellationException> { HomeSectionContributorRegistry.collectSections(false) }
    }
}
