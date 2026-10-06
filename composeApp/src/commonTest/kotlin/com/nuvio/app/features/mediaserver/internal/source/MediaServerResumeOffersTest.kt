package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.PlaybackResumeOffer
import com.nuvio.app.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.IsoTime
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.UserDataDto
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerResumeOffersTest {
    private val fake = FakeClient()
    private val id = "ms:jellyfin:$M:$U:movie:m1"

    @BeforeTest fun clean() = resetAllSourceRegistriesForTest()
    @AfterTest fun restore() = resetAllSourceRegistriesForTest()

    private fun rig(): TestRig = TestRig(clientFactory = { fake }).also { r ->
        val e = entry()
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("T"))
    }

    private fun serverItem(positionMs: Long, lastPlayedMs: Long?) = item("m1") {
        it.copy(userData = UserDataDto(playbackPositionTicks = positionMs * 10_000, lastPlayedDate = lastPlayedMs?.let(IsoTime::format)), runTimeTicks = 7_200_000L * 10_000)
    }

    @Test
    fun aServerPositionNewerThanTuvorasIsOfferedOnceWithOneItemFetch() = runTest {
        val rig = rig()
        fake.items["m1"] = serverItem(positionMs = 40 * 60_000L, lastPlayedMs = 2_000_000_000_000L)
        val source = MediaServerResumeOffers(rig.store, rig.services)
        val offer = source.offer(id, tuvoraPositionMs = 5 * 60_000L, tuvoraUpdatedAtMs = 1_900_000_000_000L, durationMs = null)
        assertEquals(PlaybackResumeOffer(40 * 60_000L), offer)
        assertEquals(listOf("m1"), fake.itemRequests, "exactly one fetch")
    }

    @Test
    fun noOfferWhenTuvoraIsTheNewerOrTheSameOrTheServerHasNothing() = runTest {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        fake.items["m1"] = serverItem(40 * 60_000L, lastPlayedMs = 1_000_000_000_000L)
        assertNull(source.offer(id, 5 * 60_000L, tuvoraUpdatedAtMs = 1_900_000_000_000L, durationMs = null), "Tuvora watched later")
        fake.items["m1"] = serverItem(5 * 60_000L + 5_000, lastPlayedMs = 2_000_000_000_000L)
        assertNull(source.offer(id, 5 * 60_000L, 1_900_000_000_000L, null), "within 30 s of each other")
        fake.items["m1"] = item("m1")
        assertNull(source.offer(id, 0, null, null), "no UserData")
    }

    @Test
    fun aFailingOrRevokedServerOffersNothingAndNeverBreaksPlayback() = runTest {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        fake.failWith = MediaServerException.Unreachable("down")
        assertNull(source.offer(id, 0, null, null))
        fake.failWith = MediaServerException.Http(401)
        assertNull(source.offer(id, 0, null, null))
        assertTrue("jellyfin:$M:$U" in rig.services.expiredSessions.value, "a 401 is noticed here too")
    }

    @Test
    fun onlyMoviesAndEpisodesAreAskedAbout() {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        assertTrue(source.handles(id, "ms"))
        assertTrue(source.handles("ms:jellyfin:$M:$U:episode:e1", "ms"))
        assertFalse(source.handles("ms:jellyfin:$M:$U:series:s1", "ms"))
        assertFalse(source.handles("tt0133093", null))
        assertFalse(source.handles("xtream:http://x|u:vod:1", "xtream"))
    }

    @Test
    fun theRegistryAsksOnlyTheOwnerAndSwallowsAFailure() = runTest {
        val rig = rig()
        fake.items["m1"] = serverItem(40 * 60_000L, 2_000_000_000_000L)
        PlaybackResumeOfferRegistry.register(object : com.nuvio.app.core.contracts.PlaybackResumeOfferSource {
            override val name = "broken"
            override fun handles(videoId: String, providerAddonId: String?) = true
            override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer? = error("boom")
        })
        PlaybackResumeOfferRegistry.register(MediaServerResumeOffers(rig.store, rig.services))
        assertEquals(PlaybackResumeOffer(40 * 60_000L), PlaybackResumeOfferRegistry.offerFor(id, "ms", 0, 1_000_000_000_000L, null))
        assertNull(PlaybackResumeOfferRegistry.offerFor("tt0133093", null, 0, null, null) , "a broken source that owns everything offers nothing and the call still returns")
    }
}
