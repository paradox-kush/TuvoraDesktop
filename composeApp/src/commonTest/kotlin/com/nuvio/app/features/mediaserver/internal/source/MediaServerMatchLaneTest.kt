package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.item
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.Query
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.TitleFacts
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.source
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerMatchLaneTest {
    @AfterTest
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val client = FakeClient()
    private val matrix = TitleFacts(ExternalIds(tmdb = "603", imdb = "tt0133093"), primary = "The Matrix", original = "The Matrix", year = 1999)
    private var facts: TitleFacts? = matrix
    private var factsAsked = 0
    private val titleFacts = MatchTitleFacts { _, _ -> factsAsked++; facts }

    private fun rig(type: MediaServerType = MediaServerType.JELLYFIN, signedIn: Boolean = true, enabled: Boolean = true) =
        TestRig(clientFactory = { client }).also { r ->
            val e = entry(type = type).copy(enabled = enabled)
            r.store.applyFromRemote(1, listOf(e))
            if (signedIn) r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
        }

    private fun lane(rig: TestRig) = MediaServerMatchLane(rig.store, rig.services, { rig.nowMs }, titleFacts)
    private fun groupId(type: MediaServerType = MediaServerType.JELLYFIN) = MediaServerIds.matchGroupId("${type.wire}:$M:$U")

    private fun movie(id: String, tmdb: String?, vararg sources: com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaSourceDto) =
        item(id, "Matrix $id", sources = sources.toList()) { it.copy(providerIds = if (tmdb != null) mapOf("Tmdb" to tmdb) else emptyMap()) }

    @Test
    fun oneGroupPerSignedInEnabledServerForMoviesAndSeriesOnly() {
        val rig = rig()
        val l = lane(rig)
        assertEquals(listOf(groupId()), l.groups("movie").map { it.sourceId })
        assertEquals("Home", l.groups("series").single().addonName)
        assertEquals(emptyList(), l.groups("tv_channel"), "live and other types are never matched")
        assertEquals(emptyList(), lane(rig(signedIn = false)).groups("movie"), "a server this device is not signed in to is never asked")
        assertEquals(emptyList(), lane(rig(enabled = false)).groups("movie"))
        val expiredRig = rig()
        expiredRig.services.onUnauthorized("jellyfin:$M:$U")
        assertEquals(emptyList(), lane(expiredRig).groups("movie"), "a revoked session offers nothing")
    }

    @Test
    fun jellyfinFindsTheMovieByTitleSearchVerifiedByProviderIdAndOffersEveryMediaSource() = runTest {
        val rig = rig()
        client.lookupAnswer = { listOf(movie("sequel", "604"), movie("m1", "603"), movie("noids", null)) }
        client.items["m1"] = movie("m1", "603", source("a", height = 2160, codec = "hevc", size = 12_400_000_000), source("b", height = 1080))
        val streams = lane(rig).streams(groupId(), "movie", "tt0133093", null, null)
        assertEquals(1, client.lookups.size)
        val q = client.lookups.single() as Query.ByTitle
        assertEquals(listOf("The Matrix", listOf(1998, 1999, 2000), "Movie"), listOf(q.searchTerm, q.years, q.includeItemType))
        assertEquals(2, streams.size, "one entry per MediaSource")
        assertEquals(listOf("4K · HEVC · 11.5 GB", "1080p · H.264 · 4.2 GB"), streams.map { it.name })
        assertTrue(streams.all { it.addonId == groupId() && it.addonName == "Home" })
        assertEquals(listOf("ms-deferred:jellyfin:$M:$U|m1|a", "ms-deferred:jellyfin:$M:$U|m1|b"), streams.map { it.url })
        assertTrue(streams.none { "TOKEN-1" in it.url.orEmpty() }, "a stream list never holds a token")
        assertNull(streams.first().behaviorHints.proxyHeaders, "Jellyfin's direct stream needs no header")
    }

    @Test
    fun embyAsksByProviderIdAndCarriesItsTokenAsAHeaderNeverInTheUrl() = runTest {
        val rig = rig(MediaServerType.EMBY)
        client.lookupAnswer = { listOf(movie("e1", "603")) }
        client.items["e1"] = movie("e1", "603", source("ms1"))
        val streams = lane(rig).streams(groupId(MediaServerType.EMBY), "movie", "tt0133093", null, null)
        assertEquals(Query.ByProviderId(listOf("tmdb.603", "imdb.tt0133093"), "Movie"), client.lookups.single())
        assertEquals("TOKEN-1", streams.single().behaviorHints.proxyHeaders?.request?.get("X-Emby-Token"))
        assertTrue("TOKEN-1" !in streams.single().url.orEmpty())
    }

    @Test
    fun aFilterTheServerIgnoredCannotSmuggleInANonMatchingTitle() = runTest {
        // Jellyfin silently ignores AnyProviderIdEquals and returns an arbitrary page; verification is what keeps it honest
        val rig = rig(MediaServerType.EMBY)
        client.lookupAnswer = { listOf(movie("x", "999"), movie("y", "998")) }
        assertEquals(emptyList(), lane(rig).streams(groupId(MediaServerType.EMBY), "movie", "tt0133093", null, null))
        assertEquals(emptyList(), client.itemRequests, "no item was fetched for an unverified candidate")
    }

    @Test
    fun theAnswerIsCachedSoAReopenedPageAndAnEpisodeSwitchCostNoLookup() = runTest {
        val rig = rig()
        client.lookupAnswer = { listOf(movie("m1", "603")) }
        client.items["m1"] = movie("m1", "603", source("a"))
        val l = lane(rig)
        l.streams(groupId(), "movie", "tt0133093", null, null)
        l.streams(groupId(), "movie", "tt0133093", null, null)
        assertEquals(1, client.lookups.size, "the second open reuses the cached match")
        rig.nowMs += com.nuvio.app.features.mediaserver.internal.policy.MatchCache.POSITIVE_TTL_MS
        l.streams(groupId(), "movie", "tt0133093", null, null)
        assertEquals(2, client.lookups.size, "after the TTL it asks again")
    }

    @Test
    fun aTitleNotOnTheServerIsRememberedBrieflyAndAFailureIsNeverRemembered() = runTest {
        val rig = rig()
        val l = lane(rig)
        assertEquals(emptyList(), l.streams(groupId(), "movie", "tt0133093", null, null))
        val firstRound = client.lookups.size
        l.streams(groupId(), "movie", "tt0133093", null, null)
        assertEquals(firstRound, client.lookups.size, "a verified 'not here' is cached")
        rig.nowMs += com.nuvio.app.features.mediaserver.internal.policy.MatchCache.NEGATIVE_TTL_MS
        client.lookupAnswer = { listOf(movie("m1", "603")) }
        client.items["m1"] = movie("m1", "603", source("a"))
        assertEquals(1, l.streams(groupId(), "movie", "tt0133093", null, null).size, "the owner added it meanwhile")

    }

    @Test
    fun anUnreachableServerIsNeverCachedAsNotHere() = runTest {
        val rig = rig()
        val l = lane(rig)
        client.failWith = MediaServerException.Unreachable("down")
        assertEquals(emptyList(), l.streams(groupId(), "movie", "tt0133093", null, null))
        client.failWith = null
        client.lookupAnswer = { listOf(movie("m1", "603")) }
        client.items["m1"] = movie("m1", "603", source("a"))
        assertEquals(1, l.streams(groupId(), "movie", "tt0133093", null, null).size, "the very next open asks again and finds it")
    }

    @Test
    fun aRetitledItemIsFoundThroughTheOriginalTitleAndTheSearchStopsAtTheFirstHit() = runTest {
        val rig = rig()
        facts = matrix.copy(primary = "Matrix (localised)", original = "The Matrix")
        client.lookupAnswer = { q -> if ((q as Query.ByTitle).searchTerm == "The Matrix") listOf(movie("m1", "603")) else emptyList() }
        client.items["m1"] = movie("m1", "603", source("a"))
        assertEquals(1, lane(rig).streams(groupId(), "movie", "tt0133093", null, null).size)
        assertEquals(listOf("Matrix (localised)", "The Matrix"), client.lookups.map { (it as Query.ByTitle).searchTerm })
    }

    @Test
    fun anItemDeletedSinceTheCachedMatchDropsTheAnswer() = runTest {
        val rig = rig()
        client.lookupAnswer = { listOf(movie("m1", "603")) }
        client.items["m1"] = movie("m1", "603", source("a"))
        val l = lane(rig)
        assertEquals(1, l.streams(groupId(), "movie", "tt0133093", null, null).size)
        client.items.remove("m1")
        assertEquals(emptyList(), l.streams(groupId(), "movie", "tt0133093", null, null))
        client.lookups.clear()
        l.streams(groupId(), "movie", "tt0133093", null, null)
        assertEquals(1, client.lookups.size, "the stale answer was forgotten")
    }

    @Test
    fun aRevokedTokenDuringTheLookupMarksTheSessionExpiredAndOffersNothing() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Http(401)
        assertEquals(emptyList(), lane(rig).streams(groupId(), "movie", "tt0133093", null, null))
        assertTrue("jellyfin:$M:$U" in rig.services.expiredSessions.value)
    }

    @Test
    fun aSeriesEpisodeResolvesThroughItsSeasonAndNeverMatchesAWrongSeason() = runTest {
        val rig = rig()
        facts = TitleFacts(ExternalIds(tmdb = "95396"), primary = "Severance", year = 2022)
        client.lookupAnswer = { listOf(item("show1", "Severance", type = "Series") { it.copy(providerIds = mapOf("Tmdb" to "95396")) }) }
        client.seasonsOf["show1"] = listOf(item("s1", "Season 1", type = "Season") { it.copy(indexNumber = 1) }, item("s2", "Season 2", type = "Season") { it.copy(indexNumber = 2) })
        client.episodesOf["show1"] = listOf(
            item("e1", "Good News About Hell", type = "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 1) },
            item("e2", "Half Loop", type = "Episode") { it.copy(indexNumber = 2, parentIndexNumber = 1) },
        )
        client.items["e2"] = item("e2", "Half Loop", type = "Episode", sources = listOf(source("srcE2"))) { it.copy(indexNumber = 2, parentIndexNumber = 1) }
        val streams = lane(rig).streams(groupId(), "series", "tmdb:95396", 1, 2)
        assertEquals("ms-deferred:jellyfin:$M:$U|e2|srcE2", streams.single().url)
        assertEquals("S1E2 · Half Loop", streams.single().title)
        assertEquals(Query.ByTitle("Severance", listOf(2021, 2022, 2023), "Series"), client.lookups.single())
        assertEquals(emptyList(), lane(rig).streams(groupId(), "series", "tmdb:95396", 3, 1), "the server has no season 3")
        assertEquals(emptyList(), lane(rig).streams(groupId(), "series", "tmdb:95396", null, null), "a series needs a season and episode")
    }

    @Test
    fun aServerWithoutSeasonItemsStillResolvesTheEpisodeFromItsEpisodeList() = runTest {
        // recorded (Jellyfin server that creates items on demand and has season listing off): GET /Shows/{id}/Seasons answers [] while
        // GET /Shows/{id}/Episodes lists every episode with ParentIndexNumber/IndexNumber
        val rig = rig()
        facts = TitleFacts(ExternalIds(imdb = "tt0000100"), primary = "Test Show", year = 2023)
        client.lookupAnswer = { listOf(item("show1", "Test Show", type = "Series") { it.copy(providerIds = mapOf("Imdb" to "tt0000100")) }) }
        client.episodesOf["show1"] = listOf(
            item("e1", "Episode 1x1", type = "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 1) },
            item("e4", "Episode 2x1", type = "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 2) },
        )
        client.items["e4"] = item("e4", "Episode 2x1", type = "Episode", sources = listOf(source("srcE4"))) { it.copy(indexNumber = 1, parentIndexNumber = 2) }
        val streams = lane(rig).streams(groupId(), "series", "tmdb:1", 2, 1)
        assertEquals("ms-deferred:jellyfin:$M:$U|e4|srcE4", streams.single().url)
        assertEquals(emptyList(), lane(rig).streams(groupId(), "series", "tmdb:1", 3, 1), "no season 3 in the episode list either")
    }

    @Test
    fun noFactsMeansNothingIsAskedOfTheServer() = runTest {
        val rig = rig()
        facts = null
        assertEquals(emptyList(), lane(rig).streams(groupId(), "movie", "weird-id", null, null))
        assertEquals(emptyList(), client.lookups)
        assertEquals(emptyList(), lane(rig).streams("ms-match:jellyfin:other:user", "movie", "tt1", null, null), "an unknown server key")
        assertEquals(emptyList(), lane(rig).streams("xtream:abc", "movie", "tt1", null, null), "not one of ours")
    }
}
