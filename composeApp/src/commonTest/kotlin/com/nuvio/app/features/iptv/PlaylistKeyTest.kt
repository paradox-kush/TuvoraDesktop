package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 0 — the shared playlist-key builder's golden vectors. The table below is byte-identical in
 * NuvioTV (`PlaylistKeyTest`, JUnit), NuvioDesktop (this file) and nuvio-web (`playlistKey.test.ts`):
 * a playlist added on any of them must get the same permanent id.
 */
class PlaylistKeyTest {

    private data class V(val type: String, val a: String, val b: String, val expected: String?)

    // ---- GOLDEN VECTORS (keep identical across Kotlin + TS) ----
    private val vectors = listOf(
        V("xtream", "http://example.com:8080", "user", "http://example.com:8080|user"),
        V("xtream", "HTTP://Example.COM:8080", "user", "http://example.com:8080|user"),
        V("xtream", "http://example.com:80", "user", "http://example.com|user"),
        V("xtream", "https://example.com:443", "user", "https://example.com|user"),
        V("xtream", "https://example.com:80", "user", "https://example.com:80|user"),
        V("xtream", "http://example.com:443", "user", "http://example.com:443|user"),
        V("xtream", "http://example.com:8080/", "user", "http://example.com:8080|user"),
        V("xtream", "http://example.com:8080/player_api.php?username=u&password=p", "user", "http://example.com:8080|user"),
        V("xtream", "example.com:8080", "user", "http://example.com:8080|user"),
        V("xtream", "Example.com", "user", "http://example.com|user"),
        V("xtream", "  http://example.com:8080  ", "  User Name  ", "http://example.com:8080|User Name"),
        V("xtream", "http://192.168.1.10:25461/c/", "u", "http://192.168.1.10:25461|u"),
        V("xtream", "http://[2001:DB8::1]:8080/", "u", "http://[2001:db8::1]:8080|u"),
        V("xtream", "http://example.com:", "u", "http://example.com|u"),
        V("xtream", "http://example.com:99999", "u", null),
        V("xtream", "", "u", null),
        V("xtream", "http://example.com", "   ", null),
        V("m3u", "http://example.com/list.m3u?user=a&pass=b", "", "m3u|http://example.com/list.m3u|ue40c292c"),
        V("m3u", "example.com/list.m3u", "", "m3u|http://example.com/list.m3u"),
        V("m3u", "HTTPS://Example.com/List.m3u", "", "m3u|HTTPS://Example.com/List.m3u"),
        V("m3u", "  http://example.com/a.m3u  ", "", "m3u|http://example.com/a.m3u"),
        V("m3u", "http://example.com:80/a.m3u", "", "m3u|http://example.com:80/a.m3u"),
        V("m3u", "   ", "", null),
        V("stalker", "http://portal.example.com:8080/stalker_portal/c/", "00:1a:79:ab:cd:ef", "stalker|http://portal.example.com:8080|00:1A:79:AB:CD:EF"),
        V("stalker", "Portal.Example.com", " 00:1A:79:AB:CD:EF ", "stalker|http://portal.example.com|00:1A:79:AB:CD:EF"),
        V("stalker", "https://portal.example.com:443/c", "00:1a:79:00:00:01", "stalker|https://portal.example.com|00:1A:79:00:00:01"),
        V("stalker", "http://portal.example.com", "", null),
        V("m3u_file", "My List.m3u", "1719000000000", "m3u_file|My List.m3u|1719000000000"),
        V("m3u_file", "  tv.m3u ", "42", "m3u_file|tv.m3u|42"),
        V("m3u_file", "", "42", null),
    )
    // ---- END GOLDEN VECTORS ----

    private fun build(v: V): String? = when (v.type) {
        "xtream" -> PlaylistKey.xtream(v.a, v.b)
        "m3u" -> PlaylistKey.m3uUrl(v.a)
        "stalker" -> PlaylistKey.stalker(v.a, v.b)
        "m3u_file" -> PlaylistKey.m3uFile(v.a, v.b.toLong())
        else -> error("unknown vector type ${v.type}")
    }

    @Test
    fun `every golden vector builds its expected key`() {
        val failures = vectors.mapNotNull { v ->
            val got = build(v)
            if (got == v.expected) null else "$v -> $got"
        }
        assertTrue(failures.isEmpty(), "golden vector mismatches:\n" + failures.joinToString("\n"))
        assertEquals(30, vectors.size, "the table is the shared oracle — keep its size in step with the twins")
    }

    private fun form(sourceType: String, serverUrl: String = "", m3uUrl: String = "", macAddress: String = "", fileName: String? = null) =
        XtreamFormInput(
            serverUrl = serverUrl, username = "", password = "", name = null, epgUrl = null, dnsProvider = "system",
            autoRefreshHours = 24, sourceType = sourceType, m3uUrl = m3uUrl, fileName = fileName, macAddress = macAddress,
        )

    @Test
    fun `the add-playlist builders mint ids with the shared key builder`() {
        val xtream = xtreamAccountFromFields("HTTP://Panel.Example.com:80/", "u", "p")!!
        assertEquals("http://panel.example.com|u", xtream.id)
        val pasted = parseXtreamAccount("http://Panel.Example.com:8080/get.php?username=u&password=p")!!
        assertEquals("http://panel.example.com:8080|u", pasted.id)
        val m3u = m3uAccountFromForm(form(sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "example.com/l.m3u?x=1"))!!
        assertEquals("m3u|http://example.com/l.m3u?x=1", m3u.id)
        val stalker = stalkerAccountFromForm(
            form(sourceType = SOURCE_TYPE_STALKER, serverUrl = "Portal.example.com/c/", macAddress = "00:1a:79:aa:bb:cc"),
        )!!
        assertEquals("stalker|http://portal.example.com|00:1A:79:AA:BB:CC", stalker.id)
        val file = m3uFileAccountFromForm(form(sourceType = SOURCE_TYPE_M3U_FILE, fileName = "tv.m3u"), uniqueSuffix = 7L)!!
        assertEquals("m3u_file|tv.m3u|7", file.id)
    }
}
