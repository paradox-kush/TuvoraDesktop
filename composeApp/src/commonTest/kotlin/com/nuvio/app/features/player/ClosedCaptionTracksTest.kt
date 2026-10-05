package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P3 (W2 device pass): a live channel's embedded CEA-608 captions showed up in the subtitle
 * picker as "Unknown (1)" — the engines name the track after its codec/MIME (libmpv "eia_608",
 * ExoPlayer "… (application/cea-608)", iOS "Subtitle 1 (eia_608)") and carry no language, so the
 * rail filed it under Unknown. A caption track gets its own "Closed captions" entry.
 */
class ClosedCaptionTracksTest {

    private fun keysFor(vararg tracks: SubtitleTrack): List<String> =
        buildSubtitleLanguageItems(
            subtitleTracks = tracks.toList(),
            addonSubtitles = emptyList(),
            preferredLanguage = "en",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            selectedLanguageKey = SubtitleOffLanguageKey,
        ).map { it.key }

    @Test
    fun androidLibmpvCaptionTrackIsClosedCaptions() {
        // extractLibmpvTracks: label = codec, language = normalizeLanguageCode(codec)
        val cc = SubtitleTrack(index = 0, id = "1", label = "eia_608", language = "eia-608")
        assertEquals(listOf(SubtitleOffLanguageKey, "__cc__"), keysFor(cc))
    }

    @Test
    fun exoPlayerCaptionTrackIsClosedCaptions() {
        val cc = SubtitleTrack(index = 0, id = "0:0", label = "Unknown (application/cea-608)", language = null)
        assertEquals(listOf(SubtitleOffLanguageKey, "__cc__"), keysFor(cc))
    }

    @Test
    fun iosMpvCaptionTrackIsClosedCaptions() {
        val cc = SubtitleTrack(index = 0, id = "1", label = "Subtitle 1 (eia_608)", language = "")
        assertEquals(listOf(SubtitleOffLanguageKey, "__cc__"), keysFor(cc))
    }

    @Test
    fun captionTrackWithARealLanguageStaysUnderThatLanguage() {
        val cc = SubtitleTrack(index = 0, id = "1", label = "eia_608", language = "en")
        assertEquals(listOf(SubtitleOffLanguageKey, "en"), keysFor(cc))
    }

    @Test
    fun plainUntaggedSubtitleIsStillUnknown() {
        val track = SubtitleTrack(index = 0, id = "2", label = "Track 1", language = null)
        assertEquals(listOf(SubtitleOffLanguageKey, SubtitleUnknownLanguageKey), keysFor(track))
    }

    @Test
    fun closedCaptionsEntryListsTheCaptionTrack() {
        val cc = SubtitleTrack(index = 0, id = "1", label = "eia_608", language = "eia-608")
        val options = buildSubtitleSelectionOptions("__cc__", listOf(cc), emptyList())
        assertEquals(listOf("1"), options.map { (it as SubtitleSelectionOption.BuiltIn).track.id })
    }

    @Test
    fun captionFormatsAreRecognisedFromLabelLanguageOrMime() {
        assertEquals(true, ClosedCaptionTracks.isClosedCaption("Unknown (application/cea-708)"))
        assertEquals(true, ClosedCaptionTracks.isClosedCaption(null, "eia-608"))
        assertEquals(false, ClosedCaptionTracks.isClosedCaption("English", "en", "3"))
    }
}
