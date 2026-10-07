package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemsEnvelopeDto
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserJson
import com.nuvio.app.features.mediaserver.internal.FakeHttp
import com.nuvio.app.features.mediaserver.internal.RealMatchedFixtures
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.json
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.TitleFacts
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The matched lane through the REAL client against RECORDED Jellyfin 12.2.0 answers ([RealMatchedFixtures]): the title
 * search, the provider-id verification (the server's `AnyProviderIdEquals` is silently ignored and returns every movie),
 * seasons -> episodes -> the episode with its MediaSources. Nothing here is hand-written server output.
 */
class MediaServerMatchLaneRealServerTest {
    private val machine = "f8b507e4bcd746b7afbd442c775e1533"
    private val user = "bc38578b7cb748748243a927751e235e"
    private val requests = mutableListOf<String>()

    private val http = FakeHttp { r ->
        requests += r.url
        val u = r.url
        when {
            "/Items?" in u && "SearchTerm=" in u && "Matrix" in u -> json(RealMatchedFixtures.J_LOOKUP_TITLE_MATRIX)
            "/Items?" in u && "SearchTerm=" in u && "Severance" in u -> json(RealMatchedFixtures.J_LOOKUP_TITLE_SEVERANCE)
            "/Items?" in u && "AnyProviderIdEquals=" in u -> json(RealMatchedFixtures.J_LOOKUP_ANYPROVIDER_IGNORED)
            "/Items/27657e067b46664f6b18c7606bf935e7" in u -> json(RealMatchedFixtures.J_ITEM_MATRIX)
            "/Items/f9674beffc5bd3ed94eae4e256424ef1" in u -> json(RealMatchedFixtures.J_ITEM_SEVERANCE_S1E2)
            "/Shows/33e60e865132660eaf355c6fd0570697/Seasons" in u -> json(RealMatchedFixtures.J_SEASONS_SEVERANCE)
            "/Shows/33e60e865132660eaf355c6fd0570697/Episodes" in u -> json(RealMatchedFixtures.J_EPISODES_SEVERANCE_S1)
            else -> json("not found: $u", 404)
        }
    }

    private fun lane(facts: TitleFacts): Pair<MediaServerMatchLane, String> {
        val rig = TestRig(http = http)
        val e = entry(type = MediaServerType.JELLYFIN, userId = user, machineId = machine, address = "http://nas:8096")
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-LIVE"))
        return MediaServerMatchLane(rig.store, rig.services, { rig.nowMs }, { _, _ -> facts }) to MediaServerIds.matchGroupId(e.serverKey)
    }

    @Test
    fun theMatrixIsFoundByTitleAndYearAndVerifiedByItsTmdbId() = runTest {
        val (lane, group) = lane(TitleFacts(ExternalIds(tmdb = "603", imdb = "tt0133093"), primary = "The Matrix", original = "The Matrix", year = 1999))
        val streams = lane.streams(group, "movie", "tt0133093", null, null)
        assertEquals(1, streams.size)
        assertTrue(streams.single().url.orEmpty().startsWith("ms-deferred:jellyfin:$machine:$user|27657e067b46664f6b18c7606bf935e7|"))
        assertTrue(requests.any { "Years=1998" in it && "1999" in it && "2000" in it }, "year +-1 window: $requests")
        assertTrue(requests.none { "TOKEN-LIVE" in it }, "the token never rides a URL: $requests")
    }

    @Test
    fun aTitleNotInTheLibraryComesBackEmptyEvenThoughTheServerReturnedRows() = runTest {
        // the recorded title search for "The Matrix" returns only The Matrix: a different TMDB id must not match it
        val (lane, group) = lane(TitleFacts(ExternalIds(tmdb = "604"), primary = "The Matrix", year = 1999))
        assertEquals(emptyList(), lane.streams(group, "movie", "tt0234215", null, null))
    }

    @Test
    fun jellyfinsIgnoredProviderIdFilterReturnsEveryMovieAndNoneOfThemMatchesAWrongId() = runTest {
        val unfiltered = MediaBrowserJson.decodeFromString<ItemsEnvelopeDto>(RealMatchedFixtures.J_LOOKUP_ANYPROVIDER_IGNORED).items
        assertTrue(unfiltered.size >= 2, "recorded: the filter was ignored and the page is not narrowed")
        val matches = com.nuvio.app.features.mediaserver.internal.policy.MatchLookupPolicy.verify(unfiltered, ExternalIds(tmdb = "603"))
        assertEquals(listOf("The Matrix"), matches.map { it.name })
    }

    @Test
    fun anEpisodeResolvesThroughRecordedSeasonsAndEpisodes() = runTest {
        val (lane, group) = lane(TitleFacts(ExternalIds(tmdb = "95396", imdb = "tt11280740"), primary = "Severance", year = 2022))
        val streams = lane.streams(group, "series", "tt11280740:1:2", 1, 2)
        assertEquals(1, streams.size)
        assertTrue(streams.single().url.orEmpty().contains("|f9674beffc5bd3ed94eae4e256424ef1|"))
        assertEquals("S1E2 · Half Loop", streams.single().title)
        assertEquals(emptyList(), lane.streams(group, "series", "tt11280740:1:9", 1, 9), "the server has no episode 9")
    }

    // ---------------- Emby 4.10.1.0 ----------------

    private val embyMachine = "f496cc314a0f448fa8c824923e572e7a"
    private val embyUser = "bd8804031c1140f2a117827397ac0912"

    private val embyHttp = FakeHttp { r ->
        requests += r.url
        val u = r.url
        when {
            "/Items?" in u && "AnyProviderIdEquals=" in u && "tmdb.603" in u -> json(RealMatchedFixtures.E_LOOKUP_ANYPROVIDER_MATRIX)
            "/Items?" in u && "AnyProviderIdEquals=" in u && "tmdb.95396" in u -> json(RealMatchedFixtures.E_LOOKUP_ANYPROVIDER_SEVERANCE)
            "/Items?" in u && "AnyProviderIdEquals=" in u -> json(RealMatchedFixtures.E_LOOKUP_ANYPROVIDER_NONE)
            "/Items/11" in u -> json(RealMatchedFixtures.E_ITEM_MATRIX)
            "/Items/15" in u -> json(RealMatchedFixtures.E_ITEM_SEVERANCE_S1E2)
            "/Shows/13/Seasons" in u -> json(RealMatchedFixtures.E_SEASONS_SEVERANCE)
            "/Shows/13/Episodes" in u -> json(RealMatchedFixtures.E_EPISODES_SEVERANCE_S1)
            else -> json("not found: $u", 404)
        }
    }

    private fun embyLane(facts: TitleFacts): Pair<MediaServerMatchLane, String> {
        val rig = TestRig(http = embyHttp)
        val e = entry(type = MediaServerType.EMBY, userId = embyUser, machineId = embyMachine, address = "http://nas:8096")
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-LIVE"))
        return MediaServerMatchLane(rig.store, rig.services, { rig.nowMs }, { _, _ -> facts }) to MediaServerIds.matchGroupId(e.serverKey)
    }

    @Test
    fun embyFindsTheMatrixWithOneExactProviderIdQueryAndCarriesItsTokenAsAHeader() = runTest {
        val (lane, group) = embyLane(TitleFacts(ExternalIds(tmdb = "603", imdb = "tt0133093"), primary = "The Matrix", year = 1999))
        val streams = lane.streams(group, "movie", "tt0133093", null, null)
        assertEquals(1, streams.size)
        assertTrue(streams.single().url.orEmpty().startsWith("ms-deferred:emby:$embyMachine:$embyUser|11|"))
        assertEquals("TOKEN-LIVE", streams.single().behaviorHints.proxyHeaders?.request?.get("X-Emby-Token"))
        assertEquals(1, requests.count { "AnyProviderIdEquals=" in it }, "one exact query, no title search: $requests")
        assertTrue(requests.none { "SearchTerm" in it })
    }

    @Test
    fun embyAnswersAnEmptyListForATitleItDoesNotHave() = runTest {
        val (lane, group) = embyLane(TitleFacts(ExternalIds(tmdb = "604", imdb = "tt9999999"), primary = "Other", year = 2001))
        assertEquals(emptyList(), lane.streams(group, "movie", "tt9999999", null, null))
    }

    @Test
    fun embyEpisodeResolvesThroughItsSeasonAndTheRecordedItem() = runTest {
        val (lane, group) = embyLane(TitleFacts(ExternalIds(tmdb = "95396", imdb = "tt11280740"), primary = "Severance", year = 2022))
        val streams = lane.streams(group, "series", "tt11280740:1:2", 1, 2)
        assertEquals(1, streams.size)
        assertTrue(streams.single().url.orEmpty().contains("|15|mediasource_15"))
    }
}
