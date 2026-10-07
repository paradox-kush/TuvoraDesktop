package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.contracts.PlaybackSessionReporter
import com.nuvio.app.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.app.core.contracts.PlaybackSessionState
import com.nuvio.app.core.contracts.resetAllSourceRegistriesForTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wave 3 / P0: the player announces start / progress / pause / stop of a playing item to the
 * PlaybackSessionReporters that own it - through the same moments as the scrobble pipeline, but not
 * behind its TMDB-identity gate (a server item has none).
 */
class PlayerSessionReportsTest {

    private class RecordingReporter(override val name: String, private val prefix: String) : PlaybackSessionReporter {
        val events = mutableListOf<String>()
        override fun handles(videoId: String, providerAddonId: String?) = videoId.startsWith("$prefix:")
        override suspend fun onStart(session: PlaybackSessionState) { events += "start ${session.videoId} @${session.positionMs}/${session.durationMs}" }
        override suspend fun onProgress(session: PlaybackSessionState, paused: Boolean) { events += "progress ${session.videoId} paused=$paused @${session.positionMs}" }
        override suspend fun onStop(session: PlaybackSessionState) { events += "stop ${session.videoId} @${session.positionMs}" }
    }

    @BeforeTest
    fun clean() = resetAllSourceRegistriesForTest()

    @AfterTest
    fun restore() = resetAllSourceRegistriesForTest()

    private fun runtimeFor(videoId: String, scope: kotlinx.coroutines.CoroutineScope) =
        PlayerScreenRuntime(testArgs(videoId)).apply {
            this.scope = scope
            playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 12_000L, durationMs = 600_000L)
        }

    @Test
    fun `a session is announced once then follows progress pause and stop`() = runTest {
        val reporter = RecordingReporter("server", "ms")
        PlaybackSessionReporterRegistry.register(reporter)
        val runtime = runtimeFor("ms:jellyfin:m:u:movie:9", this)

        assertTrue(runtime.reportSessionStart())
        assertEquals(false, runtime.reportSessionStart(), "already announced for this item")
        runtime.reportSessionProgress(paused = false)
        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 30_000L)
        runtime.reportSessionProgress(paused = true)
        runtime.reportSessionStop()
        runtime.reportSessionStop()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "start ms:jellyfin:m:u:movie:9 @12000/600000",
                "progress ms:jellyfin:m:u:movie:9 paused=false @12000",
                "progress ms:jellyfin:m:u:movie:9 paused=true @30000",
                "stop ms:jellyfin:m:u:movie:9 @30000",
            ),
            reporter.events,
            "start once, progress and pause inside the session, one stop",
        )
    }

    @Test
    fun `progress and stop before a start announce nothing`() = runTest {
        val reporter = RecordingReporter("server", "ms")
        PlaybackSessionReporterRegistry.register(reporter)
        val runtime = runtimeFor("ms:jellyfin:m:u:movie:9", this)

        runtime.reportSessionProgress(paused = false)
        runtime.reportSessionStop()
        advanceUntilIdle()

        assertEquals(emptyList(), reporter.events)
    }

    @Test
    fun `plays of other sources are never forwarded`() = runTest {
        val reporter = RecordingReporter("server", "ms")
        PlaybackSessionReporterRegistry.register(reporter)
        val runtime = runtimeFor("xtream:acc:vod:1", this)

        assertEquals(false, runtime.reportSessionStart())
        runtime.reportSessionProgress(paused = true)
        runtime.reportSessionStop()
        advanceUntilIdle()

        assertEquals(emptyList(), reporter.events)
    }

    @Test
    fun `a short placeholder clip is not announced`() = runTest {
        val reporter = RecordingReporter("server", "ms")
        PlaybackSessionReporterRegistry.register(reporter)
        val runtime = runtimeFor("ms:jellyfin:m:u:movie:9", this)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 1_000L, durationMs = 5_000L)

        assertEquals(false, runtime.reportSessionStart())
        advanceUntilIdle()
        assertEquals(emptyList(), reporter.events)
    }

    @Test
    fun `flushing watch progress ends the session and a pause reports as paused progress`() = runTest {
        val reporter = RecordingReporter("server", "ms")
        PlaybackSessionReporterRegistry.register(reporter)
        val runtime = runtimeFor("ms:jellyfin:m:u:movie:9", this)
        runtime.reportSessionStart()

        runtime.flushWatchProgress(com.nuvio.app.features.tracking.TrackingScrobbleAction.PAUSE)
        runtime.flushWatchProgress(com.nuvio.app.features.tracking.TrackingScrobbleAction.STOP)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "start ms:jellyfin:m:u:movie:9 @12000/600000",
                "progress ms:jellyfin:m:u:movie:9 paused=true @12000",
                "stop ms:jellyfin:m:u:movie:9 @12000",
            ),
            reporter.events,
        )
    }

    @Test
    fun `one failing reporter neither breaks the others nor playback`() = runTest {
        val broken = object : PlaybackSessionReporter {
            override val name = "broken"
            override fun handles(videoId: String, providerAddonId: String?) = true
            override suspend fun onStart(session: PlaybackSessionState) = error("server down")
            override suspend fun onProgress(session: PlaybackSessionState, paused: Boolean) = error("server down")
            override suspend fun onStop(session: PlaybackSessionState) = error("server down")
        }
        val healthy = RecordingReporter("healthy", "ms")
        PlaybackSessionReporterRegistry.register(broken)
        PlaybackSessionReporterRegistry.register(healthy)
        val runtime = runtimeFor("ms:jellyfin:m:u:movie:9", this)

        runtime.reportSessionStart()
        advanceUntilIdle()

        assertEquals(listOf("start ms:jellyfin:m:u:movie:9 @12000/600000"), healthy.events)
    }

    @Test
    fun `reporters refuse a duplicate name`() {
        PlaybackSessionReporterRegistry.register(RecordingReporter("server", "ms"))
        assertFailsWith<IllegalArgumentException> { PlaybackSessionReporterRegistry.register(RecordingReporter("server", "ms")) }
    }

    private fun testArgs(videoId: String) = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = videoId,
        parentMetaId = videoId,
        parentMetaType = "movie",
        providerAddonId = "ms",
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}
