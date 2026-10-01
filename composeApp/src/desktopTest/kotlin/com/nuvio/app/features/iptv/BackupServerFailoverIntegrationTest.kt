package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.stalker.StalkerClient
import com.nuvio.app.features.iptv.stalker.StalkerSession
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 0.3 integration — the real clients over a fake transport whose MAIN server refuses connections
 * (java.net.ConnectException, exactly what OkHttp throws) while the backup answers:
 *  - Xtream: login + catalog load from the backup, active = 1, stream URLs on the backup host; after the
 *    retry window with main healthy again, the next catalog call goes to main and URLs move back.
 *  - M3U link: the playlist download fails over (unknown host on main).
 *  - Stalker: browse fails over, but create_link (a playback request) NEVER does — it goes to the
 *    active portal only, and its failure leaves the state alone.
 */
class BackupServerFailoverIntegrationTest {

    private var now = 1_000L
    private lateinit var store: InMemoryServerFailoverStateStore
    private val requests = mutableListOf<String>()
    private val downHosts = mutableSetOf<String>()

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    /** A minimal Xtream panel + M3U host, any server; [downHosts] refuse connections. */
    private val fakeTransport = object : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String = serve(url)
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) {
            serve(url).lines().forEach(onLine)
        }

        private fun serve(url: String): String {
            requests += url
            val host = hostOf(url)
            if (host in downHosts) {
                if (host.endsWith(".invalid")) throw UnknownHostException(host)
                throw ConnectException("Failed to connect to $host")
            }
            val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
            return when {
                url.endsWith(".m3u") -> "#EXTM3U\n#EXTINF:-1 group-title=\"News\",BBC One\nhttp://$host/live/1.ts\n"
                action == null -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""
                action == "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                action == "get_live_streams" -> """[{"stream_id":7,"name":"BBC One","category_id":"1"}]"""
                else -> "[]"
            }
        }
    }

    @BeforeTest
    fun setUp() {
        store = InMemoryServerFailoverStateStore()
        PlaylistServerFailover.installForTest(store = store, clock = { now }, profileId = { 1 })
        IptvTransport.current = fakeTransport
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    @AfterTest
    fun tearDown() {
        PlaylistServerFailover.resetForTest()
        IptvTransport.current = IptvTransport.Platform
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    @Test
    fun `xtream main down - backup serves the catalog and streams - main wins back after the window`() = runBlocking {
        val acc = XtreamAccount(
            id = "http://xt-main.test|u", name = "P", baseUrl = "http://xt-main.test", username = "u", password = "p",
            backupUrls = listOf("http://xt-backup.test:8080"),
        )
        downHosts += "xt-main.test"

        assertTrue(XtreamClient.verify(acc).isSuccess, "login fails over to the backup")
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        assertEquals(ServerFailoverState(1, 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS), store.read(1, acc.id))

        requests.clear()
        assertEquals(listOf("News"), XtreamClient.liveCategories(acc).getOrThrow().map { it.name })
        val channels = XtreamClient.liveChannels(acc, "1").getOrThrow()
        assertEquals("http://xt-backup.test:8080/live/u/p/7.ts", channels.single().streamUrl)
        assertTrue(requests.none { hostOf(it) == "xt-main.test" }, "inside the window main is not retried: $requests")
        assertEquals("http://xt-backup.test:8080/live/u/p/7.ts", XtreamClient.liveStreamUrl(acc, 7))
        assertEquals("http://xt-backup.test:8080/movie/u/p/9.mkv", XtreamClient.movieStreamUrl(acc, 9, "mkv"))
        assertTrue(XtreamClient.liveTimeshiftUrls(acc, 7, 0L, 60).all { it.startsWith("http://xt-backup.test:8080/") })

        // The window runs out and main is healthy again: the next natural catalog call goes to main.
        downHosts.clear()
        now = 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        requests.clear()
        XtreamClient.liveCategories(acc).getOrThrow()
        assertEquals(listOf("xt-main.test"), requests.map(::hostOf))
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
        assertEquals("http://xt-main.test/live/u/p/7.ts", XtreamClient.liveStreamUrl(acc, 7))
        assertEquals(
            "http://xt-main.test/live/u/p/7.ts",
            PlaylistServerFailover.rebaseStreamUrl(acc, channels.single().streamUrl),
            "a stream url cached while on the backup is moved back to main",
        )
    }

    @Test
    fun `building a stream url never makes a request or moves the active server`() {
        val acc = XtreamAccount(
            id = "http://xt2-main.test|u", name = "P", baseUrl = "http://xt2-main.test", username = "u", password = "p",
            backupUrls = listOf("http://xt2-backup.test"),
        )
        store.write(1, acc.id, ServerFailoverState(1, now + 60_000))
        repeat(3) { XtreamClient.liveStreamUrl(acc, 7) }
        assertTrue(requests.isEmpty())
        assertEquals(ServerFailoverState(1, now + 60_000), store.read(1, acc.id))
    }

    @Test
    fun `m3u link download fails over to the backup url`() = runBlocking {
        val acc = XtreamAccount(
            id = "m3u|https://dead.invalid/list.m3u", name = "M", baseUrl = "https://dead.invalid/list.m3u",
            username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL,
            backupUrls = listOf("http://m3u-backup.test/list.m3u"),
        )
        downHosts += "dead.invalid"
        assertTrue(M3UClient.verify(acc).isSuccess)
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        assertEquals(listOf("dead.invalid", "m3u-backup.test"), requests.map(::hostOf))
        assertEquals(
            "http://m3u-backup.test/live/1.ts",
            M3UClient.liveChannels(acc, null).getOrThrow().single().streamUrl,
            "an m3u stream url is the line the serving host gave",
        )
    }

    // --- Stalker: browse fails over, create_link never does ---------------------------------------

    private val portalRequests = mutableListOf<String>()
    private val createLinkDown = mutableSetOf<String>()

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val host = hostOf(url)
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        portalRequests += "$host/$action"
        if (host in downHosts) throw ConnectException("Failed to connect to $host")
        if (action == "create_link" && host in createLinkDown) throw ConnectException("Failed to connect to $host")
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{"id":"1"}}"""
            "get_genres" -> """{"js":[{"id":"g1","title":"News"}]}"""
            "get_all_channels" -> """{"js":{"data":[{"id":"1","name":"Ch 1","tv_genre_id":"g1","cmd":"ffmpeg http://$host/ch/1"}]}}"""
            "create_link" -> """{"js":{"cmd":"ffmpeg http://$host/live/1.ts?token=x"}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun stalker(tag: String) = XtreamAccount(
        id = "stalker|http://$tag-main.test|00:1A:79:00:00:01", name = "S", baseUrl = "http://$tag-main.test",
        username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:00:00:01",
        backupUrls = listOf("http://$tag-backup.test"),
    )

    @Test
    fun `stalker browse fails over and create_link mints on the active backup portal`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = stalker("st1")
        downHosts += "st1-main.test"

        assertTrue(StalkerClient.verify(acc).isSuccess, "handshake/profile/genres fail over")
        assertEquals(1, PlaylistServerFailover.activeIndex(acc))
        assertEquals(listOf("Ch 1"), StalkerClient.liveChannels(acc, null).getOrThrow().map { it.name })

        portalRequests.clear()
        val url = StalkerClient.resolveLiveUrl(acc, 1, forceMint = true)
        assertEquals("http://st1-backup.test/live/1.ts?token=x", url)
        assertEquals(listOf("st1-backup.test/create_link"), portalRequests)
    }

    @Test
    fun `a failed create_link on main never tries a backup and never moves the active server`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
        val acc = stalker("st2")
        assertTrue(StalkerClient.verify(acc).isSuccess)
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
        StalkerClient.liveChannels(acc, null).getOrThrow()

        createLinkDown += "st2-main.test"
        portalRequests.clear()
        assertNull(StalkerClient.resolveLiveUrl(acc, 1, forceMint = true))
        assertTrue(portalRequests.none { it.startsWith("st2-backup.test") }, "no backup request: $portalRequests")
        assertEquals(0, PlaylistServerFailover.activeIndex(acc))
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
    }
}
