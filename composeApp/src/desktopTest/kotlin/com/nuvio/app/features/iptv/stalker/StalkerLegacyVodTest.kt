package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StalkerLegacyVodTest {
    private val requests = mutableListOf<Map<String, String>>()
    private val account = XtreamAccount(id = "legacy-vod-${java.util.UUID.randomUUID()}", name = "test", baseUrl = "http://legacy.test",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:58:B3:A6")
    private fun param(url: String, key: String) = Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let {
        java.net.URLDecoder.decode(it, "UTF-8")
    }.orEmpty()
    private val portal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val q = listOf("action","type","cmd","movie_id","series").associateWith { param(url,it) }
        requests += q
        when(q["action"]) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> """{"js":{"status":0}}"""
            "get_categories" -> if(q["type"] == "series") """{"js":null}""" else """{"js":[{"id":"7","title":"Library"}]}"""
            "get_ordered_list" -> if(q["movie_id"] == "10")
                """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"100","is_file":1,"cmd":"/media/file_100.mpg"}]}}"""
                else """{"js":{"total_items":2,"max_page_items":14,"data":[{"id":"10","name":"Movie","cmd":"/media/10.mpg"},{"id":"20","name":"Legacy Show","is_series":0,"series":[1,2,3],"cmd":"/media/20.mpg"}]}}"""
            "create_link" -> if(q["cmd"] == "/media/10.mpg") """{"js":{"cmd":""}}"""
                else """{"js":{"cmd":"ffmpeg http://media.test/play"}}"""
            else -> """{"js":[]}"""
        }
    }
    @BeforeTest fun before() {
        IptvContentDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        StalkerClient.clearMemoryCachesForTest()
        StalkerClient.sessionFactory = { StalkerSession(it, portal, { u,h,c -> c(portal(u,h)) }) }
    }
    @AfterTest fun after() { StalkerClient.clearMemoryCachesForTest(); StalkerClient.sessionFactory = { StalkerSession(it) } }
    @Test fun legacyShowIsExcludedFromMoviesAndPlaysAnEpisode() = runBlocking {
        assertEquals(listOf(10), StalkerClient.vodMovies(account,"7").getOrThrow().map { it.streamId })
        assertEquals(listOf(20), StalkerClient.series(account,"7").getOrThrow().map { it.seriesId })
        assertEquals(listOf(1,2,3), StalkerClient.seriesInfo(account,20).getOrThrow()!!.episodes.map { it.episodeNum })
        assertEquals("http://media.test/play", StalkerClient.resolveEpisodeUrl(account,20,1,2))
        assertEquals("2", requests.last { it["action"] == "create_link" }["series"])
    }
    @Test fun movieFallsBackToItsPlayableFileCommand() = runBlocking {
        StalkerClient.vodMovies(account,"7").getOrThrow()
        assertEquals("http://media.test/play", StalkerClient.resolveMovieUrl(account,10))
        assertEquals(listOf("/media/10.mpg","/media/file_100.mpg"), requests.filter { it["action"] == "create_link" }.map { it["cmd"] })
        assertEquals(1, requests.count { it["action"] == "get_ordered_list" && it["movie_id"] == "10" })
    }
}
