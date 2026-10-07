package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.PlaybackNegotiation
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaStreamDto
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.ServerAudioChoicePolicy
import com.nuvio.app.features.mediaserver.internal.source
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.test.runTest
import com.nuvio.app.features.player.languageMatchesPreference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Owner decision 2026-10-06: a server-built stream carries Tuvora's audio-language choice, falling back to the server's default track. */
class MediaServerAudioChoiceTest {
    @AfterTest
    fun reset() { MediaServerItemRegistry.reset(); MediaServerPlaybackSessions.reset() }

    private val client = FakeClient()
    private val rig = TestRig(clientFactory = { client }).also { r ->
        val e = entry(); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }
    private fun deferred() = MediaServerIds.deferredUrl("jellyfin:$M:$U", "m1", "srcA")
    private fun pref(vararg langs: String) = ServerAudioChoicePolicy.Preference(languages = { langs.toList() }, matches = ::languageMatchesPreference)

    /** A source the server must transcode (direct play refused - the MPEG-2 case), with an English default and a Spanish track. */
    private fun transcoded() = source("srcA", direct = false, stream = false).let {
        it.copy(
            defaultAudioStreamIndex = 1,
            mediaStreams = it.mediaStreams + listOf(
                MediaStreamDto(index = 1, type = "Audio", codec = "aac", language = "eng", isDefault = true),
                MediaStreamDto(index = 2, type = "Audio", codec = "aac", language = "spa"),
            ),
        )
    }

    @Test
    fun aTranscodeAsksForThePreferredAudioTrackByTheAppsOwnLanguageMatching() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        val url = MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("es")).resolveDeferredUrl(deferred(), forceMint = false)
        assertEquals(2, client.playbackRequests.size, "negotiated twice: the default, then the preferred track")
        assertNull(client.playbackRequests[0].second.audioStreamIndex, "the first negotiation leaves the choice to the server")
        assertEquals(2, client.playbackRequests[1].second.audioStreamIndex, "Spanish (spa) matches the app es preference")
        assertEquals("http://nas:8096/videos/i/master.m3u8?MediaSourceId=src1&ApiKey=SERVER-BUILT", url, "the server own transcode URL")
    }

    @Test
    fun theServerDefaultStandsWhenNoPreferenceMatchesOrItIsAlreadyTheDefault() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("de", "ja")).resolveDeferredUrl(deferred(), false)
        assertEquals(1, client.playbackRequests.size, "no match: one negotiation")
        client.playbackRequests.clear()
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("en")).resolveDeferredUrl(deferred(), false)
        assertEquals(1, client.playbackRequests.size, "the preference IS the default: one negotiation")
    }

    @Test
    fun aDirectPlayAsksForNothingBecauseThePlayerSeesEveryTrack() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded().copy(supportsDirectPlay = true, supportsDirectStream = true)), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("es")).resolveDeferredUrl(deferred(), false)
        assertEquals(1, client.playbackRequests.size, "direct play: one negotiation")
        assertNull(client.playbackRequests.single().second.audioStreamIndex, "no audio index asked")
    }

    @Test
    fun noPreferenceMeansTheServerDecides() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services).resolveDeferredUrl(deferred(), false)
        assertEquals(1, client.playbackRequests.size, "one negotiation")
    }
}
