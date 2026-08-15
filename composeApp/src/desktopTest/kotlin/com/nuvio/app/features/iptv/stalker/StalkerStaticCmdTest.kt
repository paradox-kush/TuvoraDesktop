package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Static-cmd playback: stock portals return the static `cmd` UNCHANGED from create_link when
 * `use_http_tmp_link` is false (portal server `itv.class.php`), and the portal's own client only
 * calls create_link for flagged rows (`/c/player.js`, mirrored by Kodi pvr.stalker). So an
 * unflagged row plays its browse-time cmd DIRECTLY — the whole request class disappears, which is
 * what matters against rate-limiting portals (request-count pinning, like StalkerRequestCountTest).
 *
 * The one-shot 401/403/410 refresh ladder stays the fallback: a static play that dies re-enters
 * the resolvers with forceMint=true and must mint EXACTLY once.
 */
class StalkerStaticCmdTest {

    private val requests = mutableListOf<String>()

    /**
     * Lineup with the three flag shapes a client meets:
     *  ch1/m1 — flags known-false + real absolute cmd  → static
     *  ch2/m2 — use_http_tmp_link=1, masked localhost cmd → mint
     *  ch3    — NO flags (legacy/odd portal)              → mint (absence of evidence)
     */
    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        requests += "$type/$action"
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{"status":0}}"""
            "get_all_channels" -> """{"js":{"data":[
                {"id":"1","name":"Static One","tv_genre_id":"g1","cmd":"ffmpeg http://cdn.portal.test/ch/1.ts","use_http_tmp_link":"0","use_load_balancing":"0"},
                {"id":"2","name":"Flagged Two","tv_genre_id":"g1","cmd":"ffrt http://localhost/ch/2","use_http_tmp_link":"1","use_load_balancing":"0"},
                {"id":"3","name":"Legacy Three","tv_genre_id":"g1","cmd":"ffmpeg http://cdn.portal.test/ch/3.ts"}
            ]}}"""
            "get_ordered_list" -> {
                val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                if (p == 1) """{"js":{"total_items":2,"max_page_items":14,"data":[
                    {"id":"11","name":"Movie Static","cmd":"auto http://cdn.portal.test/movie/11.mkv","use_http_tmp_link":0,"use_load_balancing":0},
                    {"id":"12","name":"Movie Flagged","cmd":"/media/file_12.mpg","use_http_tmp_link":1,"use_load_balancing":0}
                ]}}"""
                else """{"js":{"total_items":2,"max_page_items":14,"data":[]}}"""
            }
            "create_link" -> """{"js":{"cmd":"ffmpeg http://cdn.portal.test/minted.ts?play_token=x"}}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    @BeforeTest
    fun setUp() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    private fun createLinks() = requests.count { it.endsWith("/create_link") }

    @Test
    fun `an unflagged live channel plays its static cmd with zero create_link`() = runBlocking {
        val acc = account("sc-live-static")
        StalkerClient.liveChannels(acc, null).getOrThrow()

        val url = StalkerClient.resolveLiveUrl(acc, 1)
        assertEquals("http://cdn.portal.test/ch/1.ts", url)
        assertEquals(0, createLinks(), "static play must not touch create_link")
    }

    @Test
    fun `a flagged live channel still mints`() = runBlocking {
        val acc = account("sc-live-flagged")
        StalkerClient.liveChannels(acc, null).getOrThrow()

        val url = StalkerClient.resolveLiveUrl(acc, 2)
        assertEquals("http://cdn.portal.test/minted.ts?play_token=x", url)
        assertEquals(1, createLinks())
    }

    @Test
    fun `a row without flags keeps minting`() = runBlocking {
        val acc = account("sc-live-legacy")
        StalkerClient.liveChannels(acc, null).getOrThrow()

        val url = StalkerClient.resolveLiveUrl(acc, 3)
        assertEquals("http://cdn.portal.test/minted.ts?play_token=x", url)
        assertEquals(1, createLinks(), "absence of evidence keeps minting")
    }

    /** The refresh ladder's contract: static-then-401 re-enters with forceMint and mints ONCE. */
    @Test
    fun `forceMint overrides a static verdict and mints exactly once`() = runBlocking {
        val acc = account("sc-live-force")
        StalkerClient.liveChannels(acc, null).getOrThrow()

        assertEquals("http://cdn.portal.test/ch/1.ts", StalkerClient.resolveLiveUrl(acc, 1))
        assertEquals(0, createLinks())

        // The static URL answered 401 — the one-shot refresh asks for a REAL minted link.
        val minted = StalkerClient.resolveLiveUrl(acc, 1, forceMint = true)
        assertEquals("http://cdn.portal.test/minted.ts?play_token=x", minted)
        assertEquals(1, createLinks(), "the refresh mints exactly once")
    }

    @Test
    fun `an unflagged movie plays its static cmd`() = runBlocking {
        val acc = account("sc-vod-static")
        StalkerClient.vodMovies(acc, null).getOrThrow()
        val before = createLinks()

        val url = StalkerClient.resolveMovieUrl(acc, 11)
        assertEquals("http://cdn.portal.test/movie/11.mkv", url)
        assertEquals(before, createLinks())
    }

    /** The VOD has_files rewrite ships a relative cmd — only create_link can resolve that. */
    @Test
    fun `a flagged movie with a relative cmd mints`() = runBlocking {
        val acc = account("sc-vod-flagged")
        StalkerClient.vodMovies(acc, null).getOrThrow()
        val before = createLinks()

        val url = StalkerClient.resolveMovieUrl(acc, 12)
        assertEquals("http://cdn.portal.test/minted.ts?play_token=x", url)
        assertEquals(before + 1, createLinks())
    }

    /**
     * INTEGRATION(WP1) contract pin: DB-cached rows carry no flags yet, so a cold start (in-memory
     * caches gone, SQLite store intact) has no flag evidence and must MINT — the safe rule. When
     * WP1's persisted flag columns land, this play becomes static and this test flips with it.
     */
    @Test
    fun `a cold start play from the store mints until WP1 persists flags`() = runBlocking {
        val acc = account("sc-cold")
        StalkerClient.liveChannels(acc, null).getOrThrow()
        StalkerClient.clearMemoryCachesForTest()

        val url = StalkerClient.resolveLiveUrl(acc, 1)
        assertEquals("http://cdn.portal.test/minted.ts?play_token=x", url)
        assertEquals(1, createLinks())
    }
}
