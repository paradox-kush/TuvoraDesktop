package com.nuvio.app.features.mediaserver.internal.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerAudioChoicePolicyTest {
    private val tracks = listOf(ServerAudioChoicePolicy.Track(1, "eng"), ServerAudioChoicePolicy.Track(2, "spa"), ServerAudioChoicePolicy.Track(3, "fra"))
    private val exact: (String?, String) -> Boolean = { lang, wanted -> lang == wanted }

    @Test
    fun aPreferredLanguageThatIsNotTheServerDefaultIsAskedFor() {
        assertEquals(2, ServerAudioChoicePolicy.choose(tracks, serverDefaultIndex = 1, preferred = listOf("spa"), matches = exact), "the matching track is requested")
    }

    @Test
    fun theFirstPreferenceThatMatchesWins() {
        assertEquals(3, ServerAudioChoicePolicy.choose(tracks, 1, listOf("deu", "fra", "spa"), exact), "priority order, skipping a language the file lacks")
    }

    @Test
    fun theServerDefaultStandsWhenNoPreferenceMatches() {
        assertNull(ServerAudioChoicePolicy.choose(tracks, 1, listOf("deu", "jpn"), exact), "nothing to ask: the server default track is used")
        assertNull(ServerAudioChoicePolicy.choose(tracks, 1, emptyList(), exact), "no preference at all")
    }

    @Test
    fun nothingIsAskedWhenThePreferredTrackIsAlreadyTheServerDefault() {
        assertNull(ServerAudioChoicePolicy.choose(tracks, 2, listOf("spa"), exact), "no second negotiation for the same track")
    }

    @Test
    fun withoutAKnownServerDefaultThePreferredTrackIsStillAsked() {
        assertEquals(1, ServerAudioChoicePolicy.choose(tracks, null, listOf("eng"), exact), "unknown default")
        assertNull(ServerAudioChoicePolicy.choose(emptyList(), 1, listOf("eng"), exact), "no audio tracks listed")
    }
}
