package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.match.MatchDbDriver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Regression: a live channel from an M3U playlist added as a FILE never played on the docked Live TV
 * screen. [XtreamItemRegistry.liveStreamUrlForAsync] only recognised M3U *URL* playlists, so a file
 * playlist fell into the Xtream branch and built `/live///<sid>.ts` from its blank base URL — the
 * player spun on an address with no scheme or host. The line's own URL lives in the content DB.
 */
class M3uFileLiveUrlTest {

    private val account = XtreamAccount(
        id = "file:channels.m3u",
        name = "My file",
        baseUrl = "",
        username = "",
        password = "",
        sourceType = SOURCE_TYPE_M3U_FILE,
        fileName = "channels.m3u",
    )

    @BeforeTest
    fun setUp() {
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        XtreamRepository.installAccountsForTest(listOf(account))
    }

    @Test
    fun `a live channel from an M3U file plays its own line URL`() = runBlocking {
        IptvContentDb.beginIngest(account.id)
        IptvContentDb.insertChunk(
            account.id,
            channels = listOf(
                IptvStreamRow(sid = 7, name = "News", logo = null, tvgId = null, categoryId = "c", url = "http://cdn.example.com/news.m3u8", ext = null),
            ),
            vod = emptyList(), series = emptyList(), episodes = emptyList(),
            categories = listOf(Triple("live", "c", "General")),
        )
        IptvContentDb.finishIngest(account.id, liveCount = 1, vodCount = 0, seriesCount = 0)

        val url = XtreamItemRegistry.liveStreamUrlForAsync(XtreamItemRegistry.liveId(account.id, 7))

        assertEquals("http://cdn.example.com/news.m3u8", url)
    }
}
