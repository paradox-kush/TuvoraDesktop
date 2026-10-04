package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop twins of Mobile's StalkerRequestCountTest additions for B76 and B02 (Mobile keeps them in
 * androidHostTest; Desktop's gate is desktopTest).
 */
class StalkerLineupStreamTest {

    private val requests = mutableListOf<String>()
    private val urls = mutableListOf<String>()

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        requests += "$type/$action"
        urls += url
        when (action) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{}}"""
            "get_all_channels" -> {
                val data = (1..6).joinToString(",") {
                    """{"id":"$it","name":"Ch $it","tv_genre_id":"${if (it <= 3) "g1" else "g2"}","cmd":"ffmpeg http://localhost/ch/$it"}"""
                }
                """{"js":{"data":[$data]}}"""
            }
            "get_ordered_list" -> {
                val p = Regex("[&?]p=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                if (p <= 3) {
                    val data = listOf((p - 1) * 2 + 1, (p - 1) * 2 + 2).joinToString(",") {
                        """{"id":"$it","name":"Ch $it","cmd":"ffmpeg http://portal/ch/$it"}"""
                    }
                    """{"js":{"total_items":6,"max_page_items":2,"data":[$data]}}"""
                } else {
                    """{"js":{"total_items":6,"max_page_items":2,"data":[]}}"""
                }
            }
            else -> """{"js":[]}"""
        }
    }

    private fun account(id: String) = XtreamAccount(
        id = id, name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
    )

    @BeforeTest
    fun setUpDb() {
        com.nuvio.app.features.iptv.content.IptvContentDbDriver.openForTests =
            { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
    }

    /** B76: the lineup rides the streaming transport; the whole-request text client never sees it. */
    @Test
    fun `the lineup streams so a body the text client cannot finish still loads in one request`() = runBlocking {
        val textClientGivesUp: suspend (String, Map<String, String>) -> String = { url, h ->
            if ("action=get_all_channels" in url) {
                requests += "itv/get_all_channels(text)"
                throw IllegalStateException("Request timeout has expired [request_timeout=60000 ms]")
            }
            fakePortal(url, h)
        }
        StalkerClient.sessionFactory = {
            StalkerSession(it, textClientGivesUp, { u, h, c -> fakePortal(u, h).chunked(7).forEach(c) })
        }
        val acc = account("dt-stream")

        assertEquals(6, StalkerClient.liveChannels(acc, null).getOrThrow().size)
        assertEquals(3, StalkerClient.liveChannels(acc, "g1").getOrThrow().size)
        assertEquals(1, requests.count { it == "itv/get_all_channels" }, "$requests")
        assertEquals(0, requests.count { it == "itv/get_all_channels(text)" }, "$requests")
        assertEquals(0, requests.count { it == "itv/get_ordered_list" }, "$requests")
    }

    /** B02: a Movies row asks for its category without a genre filter (stock Ministra vod.class.php). */
    @Test
    fun `a movie row asks for its category without a genre filter`() = runBlocking {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }) }
        StalkerClient.vodMovies(account("dt-genre"), "12").getOrThrow()
        val list = urls.first { "action=get_ordered_list" in it && "type=vod" in it }
        assertTrue("category=12" in list, list)
        assertTrue("genre=%2A" in list || "genre=*" in list, list)
    }
}
