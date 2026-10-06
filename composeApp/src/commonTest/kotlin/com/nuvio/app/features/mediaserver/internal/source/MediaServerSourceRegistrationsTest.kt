package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.ContentClassifierRegistry
import com.nuvio.app.core.contracts.HomeSectionContributorRegistry
import com.nuvio.app.core.contracts.IptvContentClassifierAccess
import com.nuvio.app.core.contracts.MetaSourceAccess
import com.nuvio.app.core.contracts.OwnSourcePolicy
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.SearchProviderRegistry
import com.nuvio.app.core.contracts.StreamSourceAccess
import com.nuvio.app.core.contracts.StreamSourceRegistry
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.iptv.IptvSourceRegistrations
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The J0 hand-off contract, pinned: media servers register as ONE entry named `mediaserver` in each plural
 * source port, next to IPTV, and the two never claim each other's ids. (IPTV's own golden list lives in
 * IptvGoldenListContractTest; this is the other half.)
 */
class MediaServerSourceRegistrationsTest {
    private val ms = "ms:jellyfin:$M:$U:movie:m1"
    private val xtream = "xtream:http://line.example.com|alice:vod:7"

    @BeforeTest fun clean() = resetAllSourceRegistriesForTest()
    @AfterTest fun restore() { resetAllSourceRegistriesForTest(); MediaServerItemRegistry.reset() }

    private fun runtime(): TestRig {
        val rig = TestRig()
        rig.store.applyFromRemote(1, listOf(entry()))
        return rig
    }

    private fun registerBoth(rig: TestRig) {
        IptvSourceRegistrations.register()
        MediaServerSourceRegistrations.register(MediaServerRuntime(rig.store, rig.services, { rig.nowMs }))
    }

    @Test
    fun aServersItemsAreNeverScrobbledAndTelemetryNeverNamesTheServerOrUser() {
        val rig = runtime()
        registerBoth(rig)
        assertTrue(OwnSourcePolicy.isExcludedFromTrackingScrobble(ms))
        assertFalse(OwnSourcePolicy.isExcludedFromTrackingScrobble(xtream), "IPTV scrobbling is unchanged")
        assertFalse(OwnSourcePolicy.isExcludedFromTrackingScrobble("tmdb:603"))
        val wire = OwnSourcePolicy.telemetryId(ms, installSalt = "salt-1")
        assertFalse(M in wire || U in wire, "machine and user ids stay on the device: $wire")
        assertTrue(wire.startsWith("ms:jellyfin:") && wire.endsWith(":movie:m1"), wire)
        assertEquals(wire, OwnSourcePolicy.telemetryId(ms, "salt-1"), "stable for one install")
        assertTrue(wire != OwnSourcePolicy.telemetryId(ms, "salt-2"), "different installs cannot be joined")
        assertEquals("tt0133093", OwnSourcePolicy.telemetryId("tt0133093", "salt-1"), "everything else is untouched")
    }

    @Test
    fun theNameIsMediaserverInEveryPluralPort() {
        val rig = runtime()
        registerBoth(rig)
        assertEquals("mediaserver", MediaServerSourceRegistrations.NAME)
        assertEquals(2, SearchProviderRegistry.all.size, "IPTV + media servers")
        assertEquals(2, StreamSourceRegistry.all.size)
        assertEquals(2, ContentClassifierRegistry.all.size)
        assertEquals(listOf("mediaserver"), HomeSectionContributorRegistry.all.map { it.name })
        assertEquals(listOf("mediaserver"), PlaybackSessionReporterRegistry.all.map { it.name })
    }

    @Test
    fun aDuplicateRegistrationIsRefusedLoudly() {
        val rig = runtime()
        MediaServerSourceRegistrations.register(MediaServerRuntime(rig.store, rig.services, { rig.nowMs }))
        assertFailsWith<IllegalArgumentException> { MediaServerSourceRegistrations.register(MediaServerRuntime(rig.store, rig.services, { rig.nowMs })) }
    }

    @Test
    fun theTwoSourcesNeverClaimEachOthersIds() {
        val rig = runtime()
        registerBoth(rig)
        val streams = StreamSourceAccess.current()
        assertTrue(streams.isHandledId(ms)); assertTrue(streams.isHandledId(xtream))
        assertEquals(1, StreamSourceRegistry.all.count { it.isHandledId(ms) }, "exactly one source owns an ms: id")
        assertEquals(1, StreamSourceRegistry.all.count { it.isHandledId(xtream) })
        assertTrue(MetaSourceAccess.current().handlesId(ms)); assertTrue(MetaSourceAccess.current().handlesId(xtream))
        assertTrue(streams.isDeferredUrl("ms-deferred:jellyfin:$M:$U|m1|s")); assertFalse(streams.isDeferredUrl("https://nas/x"))
    }

    @Test
    fun ownSourcePredicatesCoverBothLanesWithoutStealingIptvs() {
        val rig = runtime()
        registerBoth(rig)
        assertTrue(OwnSourcePolicy.isOwnContentId(ms)); assertTrue(OwnSourcePolicy.isOwnContentId(xtream)); assertFalse(OwnSourcePolicy.isOwnContentId("tt0133093"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("ms")); assertTrue(OwnSourcePolicy.isOwnProviderId("ms-match:jellyfin:$M:$U"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("xtream")); assertTrue(OwnSourcePolicy.isOwnProviderId("xtream-match:http://l|a"))
        assertFalse(OwnSourcePolicy.isOwnProviderId("addon:torrentio"))
    }

    @Test
    fun theClassifierClearsACardWhoseServerWasRemovedAndBorrowsPosters() {
        val rig = runtime()
        registerBoth(rig)
        val classifier = IptvContentClassifierAccess.classifier
        assertFalse(classifier.isOrphaned(ms), "the server is still there")
        assertTrue(classifier.isOrphaned("ms:jellyfin:other-server:$U:movie:m1"))
        assertFalse(classifier.isLiveId(ms)); assertFalse(classifier.isXtreamId(ms))
        assertFalse(classifier.isXtreamStreamGroup("ms")); assertFalse(classifier.isXtreamStreamGroup("ms-match:x"))
        assertNull(classifier.posterFor(ms))
        MediaServerItemRegistry.register(MediaServerItemMapper.registered(entry(), item("m1"))!!)
        assertEquals("http://nas:8096/Items/m1/Images/Primary?tag=ptag&maxWidth=400&quality=90", classifier.posterFor(ms))
    }

    @Test
    fun onlyMediaServerPlaysReachTheSessionReporter() {
        val rig = runtime()
        registerBoth(rig)
        assertEquals(1, PlaybackSessionReporterRegistry.handlersFor(ms, "ms").size)
        assertTrue(PlaybackSessionReporterRegistry.handlersFor(xtream, "xtream").isEmpty())
        assertTrue(PlaybackSessionReporterRegistry.handlersFor("tt1", "addon:x").isEmpty())
    }

    @Test
    fun theRuntimeKeepsTheContributorSoScreensCanInvalidateIt() {
        val rig = runtime()
        val runtime = MediaServerRuntime(rig.store, rig.services, { rig.nowMs })
        assertNull(runtime.homeContributor)
        MediaServerSourceRegistrations.register(runtime)
        assertEquals("mediaserver", runtime.homeContributor?.name)
        rig.credentials.save(entry().serverKey, StoredCredential("t")) // no crash invalidating an unknown server
        runtime.homeContributor!!.invalidate("jellyfin:$M")
    }
}
