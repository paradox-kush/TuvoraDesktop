package com.nuvio.app.core.diag

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B116 golden vectors. The TV twin (`com.nuvio.tv.core.diagnostics.LogRedactionTest`) carries the
 * SAME vectors — change both together. Secrets used below: user `alice`, password `s3cr3t`,
 * MAC `00:1A:79:12:34:56`, tokens `TOK…`, debrid key `RDKEY…`.
 */
class LogRedactionTest {
    private val secrets = listOf("alice", "s3cr3t", "00:1A:79:12:34:56", "TOKabc123", "RDKEY0123456789", "jwtsig")

    private fun assertNoSecret(value: String) {
        secrets.forEach { assertFalse(value.contains(it), "secret '$it' leaked in: $value") }
    }

    @Test
    fun xtreamLiveMovieSeriesPathCredentialsAreMasked() {
        assertEquals(
            "http://line.example.com:8080/live/***/***/12345.ts",
            LogRedaction.url("http://line.example.com:8080/live/alice/s3cr3t/12345.ts"),
            "Xtream live",
        )
        assertEquals(
            "http://line.example.com/movie/***/***/987.mkv",
            LogRedaction.url("http://line.example.com/movie/alice/s3cr3t/987.mkv"),
            "Xtream movie",
        )
        assertEquals(
            "https://line.example.com/series/***/***/555.mp4",
            LogRedaction.url("https://line.example.com/series/alice/s3cr3t/555.mp4"),
            "Xtream series",
        )
    }

    @Test
    fun xtreamTimeshiftPathAndQueryFormsAreMasked() {
        assertEquals(
            "http://line.example.com:8080/timeshift/***/***/120/2026-10-03:20-00/12345.ts",
            LogRedaction.url("http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts"),
            "Xtream timeshift path",
        )
        assertEquals(
            "http://line.example.com/streaming/timeshift.php?username=***&password=***&stream=12345&start=2026-10-03:20-00&duration=120",
            LogRedaction.url(
                "http://line.example.com/streaming/timeshift.php?username=alice&password=s3cr3t&stream=12345&start=2026-10-03:20-00&duration=120",
            ),
            "Xtream timeshift.php query",
        )
    }

    @Test
    fun xtreamShortLiveFormIsMasked() {
        assertEquals(
            "http://line.example.com:8080/***/***/12345",
            LogRedaction.url("http://line.example.com:8080/alice/s3cr3t/12345"),
            "Xtream short live",
        )
    }

    @Test
    fun m3uGetPhpQueryCredentialsAreMasked() {
        assertEquals(
            "http://line.example.com/get.php?username=***&password=***&type=m3u_plus&output=ts",
            LogRedaction.url("http://line.example.com/get.php?username=alice&password=s3cr3t&type=m3u_plus&output=ts"),
            "M3U get.php",
        )
    }

    @Test
    fun stalkerMacTokenAndPlayTokenAreMasked() {
        assertEquals(
            "http://portal.example.com/stalker_portal/server/load.php?type=stb&action=create_link&mac=***&token=***",
            LogRedaction.url(
                "http://portal.example.com/stalker_portal/server/load.php?type=stb&action=create_link&mac=00:1A:79:12:34:56&token=TOKabc123",
            ),
            "Stalker portal call",
        )
        assertEquals(
            "http://portal.example.com/play/live.php?mac=***&stream=4242&extension=ts&play_token=***",
            LogRedaction.url(
                "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=4242&extension=ts&play_token=TOKabc123",
            ),
            "Stalker play link",
        )
        assertEquals(
            "ffmpeg http://portal.example.com/play/live.php?mac=***&stream=4242&play_token=***",
            LogRedaction.url("ffmpeg http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=4242&play_token=TOKabc123"),
            "Stalker cmd string",
        )
        val nestedCmd = LogRedaction.url(
            "http://portal.example.com/server/load.php?action=create_link&cmd=ffmpeg%20http%3A%2F%2Fportal.example.com%2Fplay%2Flive.php%3Fmac%3D00%3A1A%3A79%3A12%3A34%3A56%26play_token%3DTOKabc123",
        )
        assertEquals("http://portal.example.com/server/load.php?action=create_link&cmd=***", nestedCmd, "encoded cmd")
    }

    @Test
    fun addonDebridKeysAreMasked() {
        assertEquals(
            "https://torrentio.strem.fun/providers=yts,eztv|realdebrid=***|sort=qualitysize/stream/movie/tt0111161.json",
            LogRedaction.url(
                "https://torrentio.strem.fun/providers=yts,eztv|realdebrid=RDKEY0123456789|sort=qualitysize/stream/movie/tt0111161.json",
            ),
            "torrentio-style config segment",
        )
        assertEquals(
            "https://addon.example.com/***/manifest.json",
            LogRedaction.url("https://addon.example.com/eyJkZWJyaWRLZXkiOiJSREtFWTAxMjM0NTY3ODkifQ9/manifest.json"),
            "opaque base64 config segment",
        )
        assertEquals(
            "https://addon.example.com/stream/movie/tt0111161.json?apikey=***",
            LogRedaction.url("https://addon.example.com/stream/movie/tt0111161.json?apikey=RDKEY0123456789"),
            "api key query",
        )
    }

    @Test
    fun userinfoAndFragmentTokensAreMasked() {
        assertEquals(
            "https://***@dav.example.com/media/file.mkv",
            LogRedaction.url("https://alice:s3cr3t@dav.example.com/media/file.mkv"),
            "userinfo",
        )
        assertEquals(
            "https://app.example.com/callback#access_token=***&state=xyz",
            LogRedaction.url("https://app.example.com/callback#access_token=TOKabc123&state=xyz"),
            "fragment token",
        )
    }

    @Test
    fun iptvIdentityKeysMaskTheUsernameOrMac() {
        assertEquals(
            "xtream:http://line.example.com:8080|***:live:12345",
            LogRedaction.url("xtream:http://line.example.com:8080|alice:live:12345"),
            "xtream content id",
        )
        assertEquals(
            "http://line.example.com/c|***:series:5",
            LogRedaction.url("http://line.example.com/c|alice:series:5"),
            "content id whose base URL has a path",
        )
        assertEquals(
            "stalker:http://portal.example.com|***:live:4",
            LogRedaction.url("stalker:http://portal.example.com|00:1A:79:12:34:56:live:4"),
            "Stalker MAC identity",
        )
        assertEquals(
            "http://line.example.com|***",
            LogRedaction.url("http://line.example.com|alice"),
            "playlist key",
        )
        assertNoSecret(LogRedaction.text("preserved key=xtream:http://line.example.com|alice:movie:987 (live channel, local-only)"))
    }

    @Test
    fun plainUrlsStayUnchanged() {
        listOf(
            "https://image.tmdb.org/t/p/w500/kqjL17yufvn9OVLyXYpvtyrFfak.jpg",
            "https://v3-cinemeta.strem.io/meta/movie/tt0111161.json",
            "https://example.com/some-movie-title-2024/stream?quality=1080p",
            "http://127.0.0.1:8090/stream/file.mkv?link=abc&index=1&play",
            "https://torrentio.strem.fun/providers=yts,eztv|sort=qualitysize/manifest.json",
        ).forEach { plain -> assertEquals(plain, LogRedaction.url(plain), "plain URL must stay unchanged") }
        assertEquals("", LogRedaction.url(null), "null")
        assertEquals("not a url", LogRedaction.url("not a url"), "not a URL")
    }

    @Test
    fun redactionIsIdempotent() {
        val once = LogRedaction.url("http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts")
        assertEquals(once, LogRedaction.url(once), "url twice")
        val text = LogRedaction.text("Playing: http://line.example.com/get.php?username=alice&password=s3cr3t")
        assertEquals(text, LogRedaction.text(text), "text twice")
    }

    @Test
    fun freeTextUrlsHeadersTokensAndMacsAreMasked() {
        assertEquals(
            "[cplayer] Playing: http://line.example.com:8080/timeshift/***/***/120/2026-10-03:20-00/1.ts.",
            LogRedaction.text("[cplayer] Playing: http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/1.ts."),
            "mpv line with trailing period",
        )
        val headers = LogRedaction.text(
            "headers={Authorization=Bearer TOKabc123.jwtsig, Cookie=mac=00:1A:79:12:34:56; stb_lang=en, User-Agent=Tuvora}",
        )
        assertNoSecret(headers)
        assertTrue(headers.contains("User-Agent=Tuvora"), "non-secret header kept: $headers")
        assertNoSecret(LogRedaction.text("Authorization: Bearer TOKabc123"))
        assertNoSecret(LogRedaction.text("session eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.jwtsig ok"))
        assertNoSecret(LogRedaction.text("portal identity mac 00:1A:79:12:34:56 rejected"))
        assertNoSecret(LogRedaction.text("login username=alice password=s3cr3t"))
        assertEquals(
            "focus key=row-3 code=401 attempt=2",
            LogRedaction.text("focus key=row-3 code=401 attempt=2"),
            "ordinary diagnostic pairs stay legible",
        )
    }

    @Test
    fun everyCredentialVectorLeaksNothing() {
        listOf(
            "http://line.example.com:8080/live/alice/s3cr3t/12345.ts",
            "http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts",
            "http://line.example.com/get.php?username=alice&password=s3cr3t&type=m3u_plus",
            "http://line.example.com/xmltv.php?username=alice&password=s3cr3t",
            "http://line.example.com/player_api.php?username=alice&password=s3cr3t&action=get_live_streams",
            "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=1&play_token=TOKabc123",
            "https://torrentio.strem.fun/realdebrid=RDKEY0123456789/manifest.json",
            "https://alice:s3cr3t@dav.example.com/x",
        ).forEach { vector ->
            assertNoSecret(LogRedaction.url(vector))
            assertNoSecret(LogRedaction.text("failed to open $vector: HTTP 401"))
        }
    }

    @Test
    fun redactingLogWriterScrubsMessageAndThrowable() {
        val captured = mutableListOf<Pair<String, Throwable?>>()
        val sink = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                captured += message to throwable
            }
        }
        val writer = RedactingLogWriter(sink)
        writer.log(Severity.Warn, "open http://line.example.com/live/alice/s3cr3t/1.ts failed", "T", null)
        writer.log(
            Severity.Error,
            "request failed",
            "T",
            IllegalStateException("Client request(GET http://line.example.com/get.php?username=alice&password=s3cr3t) invalid"),
        )
        assertEquals(2, captured.size, "both lines forwarded")
        captured.forEach { (message, throwable) ->
            assertNoSecret(message)
            assertNull(throwable, "raw throwable must not reach the platform writer")
        }
        assertTrue(captured[1].first.contains("IllegalStateException"), "stack trace kept: ${captured[1].first}")
    }
}
