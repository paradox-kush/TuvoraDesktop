package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.EmptyResponseBodyException
import com.nuvio.app.features.addons.HttpStatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Step 0.3b — the per-type probes as the clients issue them, over a fake transport:
 *  - Xtream: parked-domain 200 HTML does not win; auth=0 surfaces immediately; a blank body is invalid;
 *  - M3U: the probe sends `Range: bytes=0-1023`, asks for at most 1 KB, and across probe + real request
 *    exactly ONE big download ever happens (to the validated winner).
 */
class FailoverProbeClientsTest {

    private val xtreamOk = """{"user_info":{"auth":1,"status":"Active"},"server_info":{"url":"x"}}"""

    private class Fake(val bodyOf: suspend (url: String) -> String) : IptvTransport {
        val texts = mutableListOf<String>()
        val prefixes = mutableListOf<Triple<String, Map<String, String>, Int>>()
        val bigStreams = mutableListOf<String>()
        override suspend fun getText(url: String, dnsProvider: String?): String { texts += url; return bodyOf(url) }
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
            bigStreams += url
            bodyOf(url).lines().forEach(onLine)
        }
        override suspend fun readPrefix(url: String, userAgent: String?, dnsProvider: String?, headers: Map<String, String>, maxBytes: Int): String {
            prefixes += Triple(url, headers, maxBytes)
            return bodyOf(url).take(maxBytes)   // a server that honours Range / a reader that stops at maxBytes
        }
    }

    @BeforeTest fun setUp() = PlaylistServerFailover.installForTest(clock = { 5_000L })
    @AfterTest fun tearDown() {
        PlaylistServerFailover.resetForTest()
        IptvTransport.current = IptvTransport.Platform
    }

    private val xtream = XtreamAccount(
        id = "probe|u", name = "P", baseUrl = "http://px-main.test", username = "u", password = "p",
        backupUrls = listOf("http://px-b1.test"),
    )

    @Test
    fun `xtream probe - valid login returns and html or a blank body is invalid and auth 0 is definitive`() = runTest {
        val bodies = mapOf("px-main.test" to xtreamOk, "px-b1.test" to "<html>parked</html>")
        IptvTransport.current = Fake { url -> bodies.getValue(url.substringAfter("://").substringBefore(':').substringBefore('/')) }
        XtreamClient.failoverProbe(xtream)                                           // valid
        assertFailsWith<FailoverInvalidResponseException> { XtreamClient.failoverProbe(xtream.copy(baseUrl = "http://px-b1.test")) }
        IptvTransport.current = Fake { """{"user_info":{"auth":0}}""" }
        assertFailsWith<FailoverAuthRejectedException> { XtreamClient.failoverProbe(xtream) }
        IptvTransport.current = Fake { throw EmptyResponseBodyException("empty") }
        assertFailsWith<FailoverInvalidResponseException> { XtreamClient.failoverProbe(xtream) }
    }

    @Test
    fun `xtream - a hung main and a parked-domain backup 1 - backup 2 serves and the parked one never gets the real request`() = runTest {
        val acc = xtream.copy(backupUrls = listOf("http://px-b1.test", "http://px-b2.test"))
        val fake = Fake { url ->
            when {
                "px-main.test" in url -> { delay(60_000); throw HttpStatusException(504, "timeout") }
                "px-b1.test" in url -> "<html>This domain is for sale</html>"
                else -> xtreamOk
            }
        }
        IptvTransport.current = fake
        PlaylistServerFailover.installForTest(clock = { 5_000L }, raceClock = { testScheduler.currentTime })
        val served = PlaylistServerFailover.run(acc, probe = { XtreamClient.failoverProbe(it) }) { a ->
            IptvTransport.current.getText(a.baseUrl + "/player_api.php?action=get_live_categories", a.dnsProvider)
            a.baseUrl
        }
        assertEquals("http://px-b2.test", served)
        assertEquals(2, PlaylistServerFailover.activeIndex(acc))
        assertTrue(fake.texts.none { it.startsWith("http://px-b1.test") && "action=" in it }, "the parked domain got only the probe: ${fake.texts}")
    }

    @Test
    fun `xtream auth 0 on a backup probe surfaces immediately and nothing fails over`() = runTest {
        val acc = xtream.copy(backupUrls = listOf("http://px-b1.test", "http://px-b2.test"))
        val fake = Fake { url ->
            when {
                "px-main.test" in url -> { delay(60_000); throw HttpStatusException(504, "timeout") }
                "px-b1.test" in url -> """{"user_info":{"auth":0}}"""
                else -> xtreamOk
            }
        }
        IptvTransport.current = fake
        PlaylistServerFailover.installForTest(clock = { 5_000L }, raceClock = { testScheduler.currentTime })
        assertFailsWith<FailoverAuthRejectedException> {
            PlaylistServerFailover.run(acc, probe = { XtreamClient.failoverProbe(it) }) { a -> IptvTransport.current.getText(a.baseUrl, a.dnsProvider) }
        }
        assertTrue(fake.texts.none { "px-b2.test" in it }, "backup 2 is never contacted: ${fake.texts}")
        assertEquals(1_500L, testScheduler.currentTime, "decided at the stagger (the probe answers instantly)")
    }

    private val m3u = XtreamAccount(
        id = "m3u|http://main.test/a.m3u", name = "M", baseUrl = "http://pm-main.test/a.m3u", username = "", password = "",
        sourceType = SOURCE_TYPE_M3U_URL, backupUrls = listOf("http://pm-b1.test/a.m3u", "http://pm-b2.test/a.m3u"),
    )

    @Test
    fun `m3u probe reads at most 1 KB with a Range header and the real download happens once - on the validated winner`() = runTest {
        val playlist = "#EXTM3U\n" + "#EXTINF:-1,Ch\nhttp://x/1.ts\n".repeat(5_000)
        val fake = Fake { url ->
            when {
                "pm-main.test" in url -> { delay(60_000); throw HttpStatusException(504, "timeout") }
                "pm-b1.test" in url -> "<!DOCTYPE html><html>parked</html>"
                else -> playlist
            }
        }
        IptvTransport.current = fake
        PlaylistServerFailover.installForTest(clock = { 5_000L }, raceClock = { testScheduler.currentTime })
        var lines = 0
        val served = PlaylistServerFailover.run(m3u, probe = { M3UClient.failoverProbe(it) }) { a ->
            IptvTransport.current.streamLines(a.baseUrl, null, null) { lines++ }
            a.baseUrl
        }
        assertEquals("http://pm-b2.test/a.m3u", served)
        assertEquals(listOf("http://pm-b1.test/a.m3u", "http://pm-b2.test/a.m3u"), fake.prefixes.map { it.first })
        assertTrue(fake.prefixes.all { (_, headers, max) -> headers["Range"] == "bytes=0-1023" && max == 1024 }, "${fake.prefixes}")
        // main's real request hung (never read a body); the only big download is the winner's.
        assertEquals(listOf("http://pm-main.test/a.m3u", "http://pm-b2.test/a.m3u"), fake.bigStreams, "main's request was cancelled before any body; one real download")
        assertEquals(playlist.lines().size, lines, "the winner's real download delivered the whole playlist")
    }

    @Test
    fun `m3u html on every backup gives up with the main server's error`() = runTest {
        val fake = Fake { url ->
            if ("pm-main.test" in url) throw HttpStatusException(503, "main down") else "<html>nope</html>"
        }
        IptvTransport.current = fake
        PlaylistServerFailover.installForTest(clock = { 5_000L }, raceClock = { testScheduler.currentTime })
        val e = assertFailsWith<HttpStatusException> {
            PlaylistServerFailover.run(m3u, probe = { M3UClient.failoverProbe(it) }) { a -> IptvTransport.current.streamLines(a.baseUrl, null, null) { } }
        }
        assertEquals(503, e.status)
        assertEquals(listOf("http://pm-main.test/a.m3u"), fake.bigStreams, "no big request was ever sent to a server that failed its probe")
    }
}
