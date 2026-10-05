package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.content.IptvStreamRow
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.OverlayDbDriver
import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B10 + F14 end to end on a real SQLite store and a real HTTP guide: the playlist's OWN guide is
 * matched by NAME for channels with no tvg-id, several EPG sources fill each other's gaps in order,
 * and a manual pick's programmes are kept.
 *
 * Red before lane G: the ingest's allow-set was tvg-ids only, so the blank-id channel below got
 * nothing (`storedNowNext` did not exist; `M3UClient.shortEpg` returned [] before even looking),
 * and only the first EPG URL was ever read.
 */
class XmltvNameMatchIngestTest {

    private lateinit var server: HttpServer
    private lateinit var overlayFile: File
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val guides = ConcurrentHashMap<String, String>()

    @BeforeTest
    fun setUp() {
        IptvContentDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:") }
        overlayFile = File.createTempFile("overlay_b10", ".db").also { it.delete() }
        OverlayDbDriver.openForTests = { androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(overlayFile.absolutePath) }
        runBlocking { IptvOverlayStore.closeForTests() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.path
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val body = guides[path]?.toByteArray()
                if (body == null) {
                    it.sendResponseHeaders(404, -1)
                } else {
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        runBlocking { IptvOverlayStore.closeForTests() }
        OverlayDbDriver.openForTests = null
        overlayFile.delete()
    }

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** A guide whose every channel airs "<name> now" across the current hour. */
    private fun guide(vararg channels: Pair<String, String>): String {
        val now = System.currentTimeMillis()
        val start = fmt.format(Date(now - 30 * 60_000L))
        val stop = fmt.format(Date(now + 30 * 60_000L))
        return buildString {
            append("<?xml version=\"1.0\"?>\n<tv>\n")
            for ((id, name) in channels) append("<channel id=\"$id\"><display-name>$name</display-name></channel>\n")
            for ((id, name) in channels) {
                append("<programme start=\"$start\" stop=\"$stop\" channel=\"$id\"><title>$name now</title></programme>\n")
            }
            append("</tv>\n")
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun account(id: String, epgUrls: String) = XtreamAccount(
        id = id, name = "b10", baseUrl = url("/playlist.m3u"),
        username = "", password = "", sourceType = "m3u_url", epgUrl = epgUrls,
    )

    private fun row(sid: Int, name: String, tvg: String?) =
        IptvStreamRow(sid = sid, name = name, logo = null, tvgId = tvg, categoryId = "1", url = "http://x/$sid.ts", ext = null)

    private suspend fun lineup(id: String, vararg rows: IptvStreamRow) =
        IptvContentDb.replaceLiveLineup(id, rows.toList(), listOf("1" to "UK"))

    @Test
    fun `a channel with no tvg-id gets its playlist guide by name`() = runBlocking {
        guides["/guide.xml"] = guide("bbc1.uk" to "BBC One", "cnn.us" to "CNN", "itv1.uk" to "ITV")
        val acc = account("m3u|b10-name", url("/guide.xml"))
        lineup(acc.id, row(1, "UK: BBC One FHD", ""), row(2, "CNN", "cnn.us"), row(3, "UK: ITV +1", null))

        assertTrue(XmltvClient.ensureEpg(acc, force = true), "ingest stored programmes")

        assertEquals("BBC One now", XmltvClient.storedNowNext(acc, 1).firstOrNull()?.title, "blank tvg-id, matched by name")
        assertEquals("CNN now", XmltvClient.storedNowNext(acc, 2).firstOrNull()?.title, "tvg-id still wins")
        assertEquals(emptyList(), XmltvClient.storedNowNext(acc, 3), "+1 never shows its base channel's times")
        val census = XmltvClient.census(acc)!!
        assertEquals(1, census.byId, "census by id")
        assertEquals(1, census.byName, "census by name")
    }

    @Test
    fun `several EPG sources fill gaps in priority order and stop once nothing is left`() = runBlocking {
        guides["/a.xml"] = guide("bbc1.uk" to "BBC One")
        guides["/b.xml"] = guide("bbc1.uk" to "BBC One (feed B)", "cnn.us" to "CNN")
        guides["/c.xml"] = guide("sky1.uk" to "Sky One")
        val acc = account(
            "m3u|b10-multi",
            url("/a.xml") + "\n" + url("/b.xml") + "," + url("/c.xml"),
        )
        lineup(acc.id, row(1, "BBC One", null), row(2, "CNN HD", null))

        assertTrue(XmltvClient.ensureEpg(acc, force = true))

        assertEquals("BBC One now", XmltvClient.storedNowNext(acc, 1).firstOrNull()?.title, "first source wins")
        assertEquals("CNN now", XmltvClient.storedNowNext(acc, 2).firstOrNull()?.title, "second source fills the gap")
        assertEquals(null, hits["/c.xml"], "a third source is not downloaded once every channel matched")
        val picker = XmltvClient.guideChannels(acc, "bbc")
        assertEquals(listOf(0, 1), picker.map { it.sourceIndex }, "the picker lists both sources' BBC One, priority first")
    }

    @Test
    fun `a manually picked guide channel's programmes are kept even when nothing auto-matched it`() = runBlocking {
        guides["/guide.xml"] = guide("bbc1.uk" to "BBC One", "itv1plus1.uk" to "ITV1 Plus One")
        val acc = account("m3u|b10-pick", url("/guide.xml"))
        lineup(acc.id, row(1, "UK: ITV +1", null))
        IptvOverlayStore.setEpgOverride(1, "fp:v1:itv", acc.id, "itv1plus1.uk", "ITV1 Plus One", 100)

        XmltvClient.ensureEpg(acc, force = true)

        val key = IptvContentDb.epgGuideKeyForGuideId(acc.id, "itv1plus1.uk")
        assertEquals("itv1plus1.uk", key)
        assertEquals("ITV1 Plus One now", IptvContentDb.epgAround(acc.id, key!!, System.currentTimeMillis(), 1).firstOrNull()?.title)
        assertEquals(emptyList(), XmltvClient.storedNowNext(acc, 1), "no automatic match: only the pick shows it")
    }
}
