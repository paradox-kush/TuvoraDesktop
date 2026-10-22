package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sync 14: upstream's in-controls party UI replaced the fork's window UI. The fork's fixes were pinned against the
 * old UI; this pins the same behaviour through the runtime paths the native controls (party.js included) now drive:
 * play/pause, +-10 s, double-tap, scrub and skip-intro go through the party, host-only blocks a guest, a member's
 * pause reaches the others, and a refused play request resyncs the native button.
 */
class NativeControlsPartyRoutingTest {
    private class Recorder : PlayerEngineController {
        val seeks = mutableListOf<Long>()
        var plays = 0
        var pauses = 0
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun seekBy(offsetMs: Long) {}
        override fun retry() {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) {}
        override fun selectAudioTrack(index: Int) {}
        override fun selectSubtitleTrack(index: Int) {}
        override fun setSubtitleUri(url: String) {}
        override fun clearExternalSubtitle() {}
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {}
    }

    @AfterTest
    fun leaveParty() = PartyRepository.seedForTesting(null, null)

    private fun runtime(controller: Recorder, playing: Boolean): PlayerScreenRuntime =
        PlayerScreenRuntime(args()).apply {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            playerController = controller
            shouldPlay = playing
            playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false, isPlaying = playing, positionMs = 60_000L, durationMs = 3_600_000L,
            )
        }

    /** Joins a party on the runtime's title, paused at 1:00 so the expected position does not drift under the test. */
    private fun join(runtime: PlayerScreenRuntime, hostOnly: Boolean, host: Boolean) {
        settleRepository()
        val media = PartyMedia(type = "movie", metaId = "tt1234567", metaType = "movie", title = "T")
        val me = PartyMember(id = "me", displayName = "Me")
        val other = PartyMember(id = "other", displayName = "Other")
        val party = ActiveParty(
            id = "p", code = "ABC234", memberId = me.id, hostMemberId = if (host) me.id else other.id,
            members = listOf(me, other), presence = emptyMap(), media = media, maxMembers = 3, connected = true,
            hostOnly = hostOnly,
        )
        val playback = PartyPlayback(
            seq = 1, memberId = other.id, paused = true, positionMs = 60_000L, rate = 1f,
            at = PartyRepository.nowMs(), mediaKey = media.key,
        )
        PartyRepository.seedForTesting(party, playback)
    }

    /** The repository's own init drops a party while signed out; let that first emission pass before seeding. */
    private fun settleRepository() {
        PartyRepository.nowMs()
        Thread.sleep(500)
    }

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }.also { assertTrue(condition(), what) }

    @Test
    fun `a members pause reaches the others`() {
        val controller = Recorder()
        val runtime = runtime(controller, playing = true)
        join(runtime, hostOnly = false, host = false)

        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.TogglePlayback), "consumed by the runtime, not the native fallback")
        assertFalse(runtime.shouldPlay)
        await("party paused by the member") { PartyRepository.playback.value?.let { it.seq > 1 && it.paused && it.memberId == "me" } == true }
    }

    @Test
    fun `plus and minus ten seconds, double tap and scrub are published for a member`() {
        val controller = Recorder()
        val runtime = runtime(controller, playing = false)
        join(runtime, hostOnly = false, host = false)

        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.SeekForward))
        await("seek +10 reached the party") { PartyRepository.playback.value?.positionMs == 70_000L }
        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.DoubleTapSeekBack), "double tap routes through the party")
        runtime.handlePlayerControlsEvent("scrubFinish", 80_000.0)
        await("scrub reached the party") { PartyRepository.playback.value?.positionMs == 80_000L }
        assertTrue(80_000L in controller.seeks, controller.seeks.toString())
    }

    @Test
    fun `skip intro seeks the party for a member`() {
        val controller = Recorder()
        val runtime = runtime(controller, playing = false)
        join(runtime, hostOnly = false, host = false)
        val intro = SkipInterval(startTime = 0.0, endTime = 90.0, type = "intro", provider = "test")
        runtime.skipIntervals = listOf(intro)
        runtime.activeSkipInterval = intro

        runtime.handlePlayerControlsEvent("skipInterval", 0.0)
        await("skip reached the party") { PartyRepository.playback.value?.positionMs == 90_000L }
    }

    @Test
    fun `host only blocks a guest from moving or starting the party but not from going back for themselves`() {
        val controller = Recorder()
        val runtime = runtime(controller, playing = false)
        join(runtime, hostOnly = true, host = false)
        val before = PartyRepository.playback.value

        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.TogglePlayback))
        assertEquals(0, controller.plays, "a guest may not start a paused host-only party")
        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.SeekForward))
        runtime.handlePlayerControlsEvent("scrubFinish", 80_000.0)
        val intro = SkipInterval(startTime = 0.0, endTime = 90.0, type = "intro", provider = "test")
        runtime.skipIntervals = listOf(intro)
        runtime.activeSkipInterval = intro
        runtime.handlePlayerControlsEvent("skipInterval", 0.0)
        assertTrue(controller.seeks.none { it > 60_000L }, "no forward seek past the party: ${controller.seeks}")
        assertEquals(before, PartyRepository.playback.value, "a guest publishes nothing")

        assertTrue(runtime.handlePlayerControlsAction(PlayerControlsAction.SeekBack))
        assertTrue(50_000L in controller.seeks, "a guest may rewind for themselves: ${controller.seeks}")
        assertTrue(runtime.partyBehind)
    }

    @Test
    fun `a refused play request makes the native button drop its optimistic state`() {
        val controller = Recorder()
        val runtime = runtime(controller, playing = false)
        join(runtime, hostOnly = true, host = false)
        val token = runtime.playerControlsPlaybackResyncToken

        runtime.handlePlayerControlsEvent("setPlaybackState", 1.0)
        assertFalse(runtime.shouldPlay)
        assertEquals(token + 1, runtime.playerControlsPlaybackResyncToken)

        runtime.handlePlayerControlsEvent("setPlaybackState", 0.0)
        assertEquals(token + 1, runtime.playerControlsPlaybackResyncToken, "an honoured request needs no resync")
    }

    private fun args() = PlayerScreenArgs(
        profileId = 1, title = "Title", sourceUrl = "https://example.com/video.mp4", sourceAudioUrl = null,
        sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(), streamType = null, providerName = "Provider",
        streamTitle = "Source", streamSubtitle = null, initialBingeGroup = null, pauseDescription = null, onBack = {},
        onOpenInExternalPlayer = null, onOpenExternalUrl = null, modifier = Modifier, logo = null, poster = null,
        background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
        contentType = "movie", videoId = "tt1234567", parentMetaId = "tt1234567", parentMetaType = "movie",
        providerAddonId = null, torrentInfoHash = null, torrentFileIdx = null, torrentFilename = null,
        torrentTrackers = emptyList(), initialPositionMs = 0L, initialProgressFraction = null,
    )
}
