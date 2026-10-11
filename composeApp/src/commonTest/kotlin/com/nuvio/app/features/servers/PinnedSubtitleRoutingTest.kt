package com.nuvio.app.features.servers

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.RelayedSubtitleController
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.streams.StreamSubtitle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** External subtitle files of a pinned server go through the same relay as the video. */
class PinnedSubtitleRoutingTest {
    private val authority = "nas.local:8920"
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    private val upstream = object : ServerHttp {
        override fun clientFor(url: String) = HttpClient(MockEngine { respond(srt, HttpStatusCode.OK) }) { followRedirects = false; install(HttpTimeout) }
    }

    private class Recording : PlayerEngineController {
        val subtitleUris = mutableListOf<String>()
        var played = 0
        override fun play() { played++ }
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun seekBy(offsetMs: Long) {}
        override fun retry() {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) {}
        override fun selectAudioTrack(index: Int) {}
        override fun selectSubtitleTrack(index: Int) {}
        override fun setSubtitleUri(url: String) { subtitleUris += url }
        override fun clearExternalSubtitle() {}
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {}
    }

    private fun subtitle(url: String) = StreamSubtitle(url = url, language = "eng", name = "English")

    @Test
    fun `a direct source leaves subtitles untouched`() {
        val playback = PinnedPlayback("https://cdn.example/v.mkv", null)
        val list = listOf(subtitle("https://cdn.example/s.srt"))
        assertSame(list, playback.rewriteSubtitles(list), "same list")
        assertEquals("https://cdn.example/s.srt", playback.rewrite("https://cdn.example/s.srt"), "same url")
    }

    @Test
    fun `subtitles on the pinned server map to the relay and others do not`() = runTest {
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val playback = PinnedPlayback(session.rewrite("https://$authority/Videos/1/stream")!!, session)
            val rewritten = playback.rewriteSubtitles(
                listOf(subtitle("https://$authority/Videos/1/ms/Subtitles/3/0/Stream.srt?api_key=k"), subtitle("https://other.example/s.srt")),
            )
            assertEquals("http://127.0.0.1:${session.port}/${session.token}/Videos/1/ms/Subtitles/3/0/Stream.srt?api_key=k", rewritten[0].url, "pinned server subtitle")
            assertEquals("https://other.example/s.srt", rewritten[1].url, "another host keeps its url")
            assertEquals("eng", rewritten[0].language, "metadata kept")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `a subtitle file is served through the relay`() = runTest {
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val url = PinnedPlayback("x", session).rewrite("https://$authority/Videos/1/ms/Subtitles/3/0/Stream.srt")
            val body = withContext(Dispatchers.Default) {
                val selector = SelectorManager(Dispatchers.Default)
                try {
                    val socket = aSocket(selector).tcp().connect("127.0.0.1", session.port)
                    val write = socket.openWriteChannel(autoFlush = true)
                    write.writeStringUtf8("GET ${url.substringAfter("127.0.0.1:${session.port}")} HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n\r\n")
                    val text = socket.openReadChannel().readRemaining().readByteArray().decodeToString()
                    socket.close()
                    text
                } finally {
                    selector.close()
                }
            }
            assertTrue(body.startsWith("HTTP/1.1 200"), "ok")
            assertTrue(body.endsWith(srt), "the subtitle text arrived")
        } finally {
            session.dispose()
        }
    }

    @Test
    fun `the controller hands the engine the relayed subtitle url`() = runTest {
        val session = PinnedMediaRelay(upstream).open(authority)
        try {
            val playback = PinnedPlayback("x", session)
            val inner = Recording()
            val controller = RelayedSubtitleController(inner, playback::rewrite)
            controller.setSubtitleUri("https://$authority/Videos/1/ms/Subtitles/3/0/Stream.srt")
            controller.setSubtitleUri("https://addon.example/en.srt")
            controller.play()
            assertEquals(
                listOf(
                    "http://127.0.0.1:${session.port}/${session.token}/Videos/1/ms/Subtitles/3/0/Stream.srt",
                    "https://addon.example/en.srt",
                ),
                inner.subtitleUris,
                "pinned subtitle relayed, add-on subtitle untouched",
            )
            assertEquals(1, inner.played, "everything else is forwarded")
        } finally {
            session.dispose()
        }
    }
}
