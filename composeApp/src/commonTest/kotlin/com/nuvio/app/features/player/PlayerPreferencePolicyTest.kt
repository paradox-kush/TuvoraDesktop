package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPreferencePolicyTest {

    // --- F37: remember per series, fall back to global ---

    @Test
    fun seriesPictureWinsOverGlobalModeWhenRemembering() {
        val stored = PersistedPlayerTrackPreference(resizeMode = "Zoom", zoomScaleX = 1.2f)
        val choice = PlayerPreferencePolicy.initialPicture(true, stored, PlayerResizeMode.Fit)
        assertEquals(PlayerResizeMode.Zoom, choice.resizeMode)
        assertEquals(VideoZoom(scaleX = 1.2f), choice.zoom)
    }

    @Test
    fun noSeriesMemoryFallsBackToGlobalModeAndNoZoom() {
        val choice = PlayerPreferencePolicy.initialPicture(true, null, PlayerResizeMode.Fill)
        assertEquals(PictureChoice(PlayerResizeMode.Fill, VideoZoom.IDENTITY), choice)
    }

    @Test
    fun rememberOffIgnoresSeriesMemoryEntirely() {
        val stored = PersistedPlayerTrackPreference(resizeMode = "Zoom", zoomScaleX = 1.5f, audioLanguage = "ja")
        val choice = PlayerPreferencePolicy.initialPicture(false, stored, PlayerResizeMode.Fit)
        assertEquals(PictureChoice(PlayerResizeMode.Fit, VideoZoom.IDENTITY), choice)
        assertNull(PlayerPreferencePolicy.seriesMemory(false, stored))
        assertEquals(stored, PlayerPreferencePolicy.seriesMemory(true, stored))
    }

    @Test
    fun unknownStoredModeFromAnotherPlatformFallsBackToGlobal() {
        // Platforms differ (desktop has Stretch, TV has more); a value this build lacks must not stick.
        val stored = PersistedPlayerTrackPreference(resizeMode = "Panorama")
        val choice = PlayerPreferencePolicy.initialPicture(true, stored, PlayerResizeMode.Fit)
        assertEquals(PlayerResizeMode.Fit, choice.resizeMode)
    }

    @Test
    fun inPlayerChoicesPersistOnlyWhenRememberingWithASeriesKey() {
        assertTrue(PlayerPreferencePolicy.persistsSeriesChoice(true, "tt0388629"))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(false, "tt0388629"))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(true, ""))
        assertFalse(PlayerPreferencePolicy.persistsSeriesChoice(true, null))
    }

    @Test
    fun withPictureKeepsTrackMemoryAndStoresIdentityZoomAsNothing() {
        val current = PersistedPlayerTrackPreference(audioLanguage = "ja", subtitleLanguage = "en")
        val zoomed = PlayerPreferencePolicy.withPicture(current, PlayerResizeMode.Fit, VideoZoom(scaleX = 1.33f))
        assertEquals("ja", zoomed.audioLanguage)
        assertEquals("en", zoomed.subtitleLanguage)
        assertEquals("Fit", zoomed.resizeMode)
        assertEquals(1.33f, zoomed.zoomScaleX)
        assertEquals(1f, zoomed.zoomScaleY)

        val reset = PlayerPreferencePolicy.withPicture(zoomed, PlayerResizeMode.Zoom, VideoZoom.IDENTITY)
        assertEquals("Zoom", reset.resizeMode)
        assertNull(reset.zoomScaleX)
        assertNull(reset.zoomScaleY)
        assertNull(reset.zoomPanX)
        assertNull(reset.zoomPanY)
    }

    // --- F37: tracks are matched by language, not by a reused id ---

    @Test
    fun reusedSubtitleIdDoesNotRestoreAnotherLanguage() {
        // mpv numbers tracks 1..n per file, so episode 2's track "3" can be a different language.
        val nextEpisode = listOf(
            SubtitleTrack(0, "2", "English", "en"),
            SubtitleTrack(1, "3", "French", "fr"),
            SubtitleTrack(2, "4", "Spanish", "es"),
        )
        val remembered = PersistedPlayerTrackPreference(
            subtitleType = PersistedSubtitleSelectionType.INTERNAL,
            subtitleTrackId = "3",
            subtitleLanguage = "es",
            subtitleName = "Spanish",
        )
        assertEquals(2, findPersistedSubtitleTrackIndex(nextEpisode, remembered))
    }

    @Test
    fun subtitleIdStillWinsWhenItsLanguageAgrees() {
        val tracks = listOf(
            SubtitleTrack(0, "2", "English SDH", "en"),
            SubtitleTrack(1, "3", "English", "en"),
        )
        val remembered = PersistedPlayerTrackPreference(subtitleTrackId = "3", subtitleLanguage = "en")
        assertEquals(1, findPersistedSubtitleTrackIndex(tracks, remembered))
    }

    @Test
    fun untaggedSubtitleIdMatchStillRestores() {
        // No language on either side: the id is the only signal left, keep using it.
        val tracks = listOf(SubtitleTrack(0, "1", "Track 1"), SubtitleTrack(1, "2", "Track 2"))
        assertEquals(1, findPersistedSubtitleTrackIndex(tracks, PersistedPlayerTrackPreference(subtitleTrackId = "2")))
    }

    @Test
    fun forcedSubtitleChoiceRestoresTheForcedTrackOfTheSameLanguage() {
        val tracks = listOf(
            SubtitleTrack(0, "1", "English", "en"),
            SubtitleTrack(1, "2", "English (Forced)", "en", isForced = true),
        )
        val remembered = PersistedPlayerTrackPreference(subtitleTrackId = "9", subtitleLanguage = "en", subtitleIsForced = true)
        assertEquals(1, findPersistedSubtitleTrackIndex(tracks, remembered))
    }
}
