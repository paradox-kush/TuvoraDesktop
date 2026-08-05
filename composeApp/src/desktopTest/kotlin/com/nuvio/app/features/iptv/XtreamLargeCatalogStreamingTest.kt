package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.ResponseTooLargeException
import com.nuvio.app.features.addons.httpGetText
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog fetch driven end to end over a real socket, through the real Android transport
 * (OkHttp + `httpStreamLines` + the catalog splitter) rather than an injected fake.
 *
 * The bug this pins was invisible to a fake: `httpGetText` read the WHOLE response into one byte
 * array, so a real provider's catalog asked for ~27 MB in a single allocation on a phone already
 * holding ~80 MB of heap, and got
 *
 *     OutOfMemoryError: Failed to allocate a 26891064 byte allocation
 *
 * which was caught — so TMDB enrichment for that provider silently never happened while the
 * allocation churn stalled the UI behind blocking GCs. Only a real body of real size, arriving in
 * real chunks, exercises that.
 *
 * [CATALOG_ITEMS] is overridable so this stays a fast regression test by default but can be run at
 * the size that actually reproduced the failure:
 *
 *     ./gradlew :composeApp:testAndroidHostTest --tests '*XtreamLargeCatalogStreamingTest*' \
 *         -Dcatalog.items=175000
 */
class XtreamLargeCatalogStreamingTest {

    private companion object {
        /** Big enough to cross the transport's 1 MB chunk cap many times over. */
        val CATALOG_ITEMS = System.getProperty("catalog.items")?.toInt() ?: 20_000
    }

    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** Serves [handler] on an ephemeral port and returns the base URL. */
    private fun serve(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange -> exchange.use { handler(it) } }
        s.executor = null
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) =
        try { block(this) } finally { close() }

    private fun account(baseUrl: String) = XtreamAccount(
        id = "big", name = "big panel", baseUrl = baseUrl, username = "u", password = "p",
    )

    /** One realistic XUI VOD row — the noise fields matter, they are most of the payload. */
    private fun vodRow(i: Int): String =
        """{"num":$i,"name":"Test Movie $i (20${10 + i % 20})","stream_type":"movie",""" +
            """"stream_id":$i,"stream_icon":"http://cdn.example/images/poster_$i.jpg",""" +
            """"rating":"7.5","rating_5based":3.8,"added":"160000$i","category_id":"${i % 40}",""" +
            """"container_extension":"mkv","custom_sid":"","direct_source":"","tmdb":"${900000 + i}"}"""

    /** Writes the array straight to the socket so the TEST never holds it whole either. */
    private fun writeCatalog(out: OutputStream, items: Int): Long {
        var bytes = 0L
        fun emit(s: String) {
            val b = s.toByteArray()
            out.write(b)
            bytes += b.size.toLong()
        }
        emit("[")
        for (i in 1..items) {
            if (i > 1) emit(",")
            emit(vodRow(i))
        }
        emit("]")
        out.flush()
        return bytes
    }

    @Test
    fun indexesAFullSizeCatalogWithoutEverHoldingItWhole() {
        var servedBytes = 0L
        val base = serve { exchange ->
            assertTrue(
                exchange.requestURI.query.contains("action=get_vod_streams"),
                "unexpected request: ${exchange.requestURI}",
            )
            exchange.sendResponseHeaders(200, 0)   // 0 = chunked, like a real panel streaming out
            servedBytes = writeCatalog(exchange.responseBody, CATALOG_ITEMS)
        }

        val items = runBlocking { XtreamClient.vodIndexItems(account(base)).getOrThrow() }

        println(
            "served ${servedBytes / 1024 / 1024}MB / $servedBytes bytes as $CATALOG_ITEMS items " +
                "-> ${items.size} indexed"
        )
        assertEquals(CATALOG_ITEMS, items.size)

        // Spot-check both ends: fields have to survive chunk boundaries falling anywhere.
        assertEquals(1, items.first().sid)
        assertEquals("Test Movie 1 (2011)", items.first().name)
        assertEquals(2011, items.first().year)
        assertEquals(900001, items.first().tmdb, "tmdb arrived as a quoted string")
        assertEquals("mkv", items.first().ext)
        assertEquals(CATALOG_ITEMS, items.last().sid)
        assertEquals(900000 + CATALOG_ITEMS, items.last().tmdb)
    }

    @Test
    fun aConnectionDroppedMidCatalogFailsInsteadOfReturningAPartialList() {
        // The caller diffs this list against the stored index and DELETES what is missing, so a
        // half-received catalog coming back as a complete one would quietly wipe titles.
        val base = serve { exchange ->
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("[".toByteArray())
            repeat(500) { i -> out.write((vodRow(i + 1) + ",").toByteArray()) }
            out.flush()
            // …and hang up without closing the array.
        }

        val result = XtreamClient.let { runBlocking { it.vodIndexItems(account(base)) } }
        assertTrue(result.isFailure, "a truncated catalog must not look like a complete one")
    }

    @Test
    fun aPanelErrorObjectIsAFailureNotAnEmptyCatalog() {
        // A panel that errors mid-session answers {"user_info":…} where the array should be. Read
        // as an empty catalog it would look like "this provider has no movies" — and an empty list
        // is accepted on a first build, so it would stick.
        val base = serve { exchange ->
            val body = """{"user_info":{"auth":0,"status":"Expired"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }

        val result = runBlocking { XtreamClient.vodIndexItems(account(base)) }
        assertTrue(result.isFailure, "a panel error object must not read as an empty catalog")
    }

    @Test
    fun anOversizedBodyOnTheStringPathIsRefusedBeforeItIsRead() {
        // The backstop for everything still going through httpGetText. Declaring a length over the
        // cap is refused up front, so nothing is allocated and nothing is downloaded.
        val base = serve { exchange ->
            exchange.sendResponseHeaders(200, 64L * 1024 * 1024)
            // deliberately never written — the guard must fire on the header alone
        }

        val err = runCatching { runBlocking { httpGetText("$base/player_api.php") } }.exceptionOrNull()
        assertTrue(
            err is ResponseTooLargeException,
            "expected ResponseTooLargeException, got ${err?.let { it::class.simpleName + ": " + it.message }}",
        )
    }
}
