package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UX91 — an unnamed M3U playlist is named from its file, not its (possibly dead) host. */
class PlaylistDefaultNameTest {

    private fun m3uForm(url: String, name: String? = null) = XtreamFormInput(
        serverUrl = "", username = "", password = "", name = name, epgUrl = null, dnsProvider = "system",
        autoRefreshHours = 24, sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = url,
    )

    @Test
    fun `a telling file name becomes the playlist name`() {
        assertEquals("uk sports", PlaylistDefaultName.fromM3uUrl("http://dead.invalid/lists/uk_sports.m3u"))
        assertEquals("Movies 2026", PlaylistDefaultName.fromM3uUrl("https://cdn.example/Movies%202026.m3u8?token=x"))
        assertEquals("family-pack", PlaylistDefaultName.fromM3uUrl("cdn.example/family-pack"))
    }

    @Test
    fun `generic or meaningless file names fall back`() {
        assertNull(PlaylistDefaultName.fromM3uUrl("http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus"))
        assertNull(PlaylistDefaultName.fromM3uUrl("http://h.example/playlist.m3u"))
        assertNull(PlaylistDefaultName.fromM3uUrl("http://h.example/"))
        assertNull(PlaylistDefaultName.fromM3uUrl("http://h.example"))
        assertNull(PlaylistDefaultName.fromM3uUrl("http://h.example/12345.m3u"))
        assertNull(PlaylistDefaultName.fromM3uUrl("http://h.example/a8f93c0d1e2b4f5a6c7d.m3u"))
    }

    @Test
    fun `the M3U form uses the file name and keeps the host as the fallback`() {
        assertEquals("uk sports", m3uAccountFromForm(m3uForm("http://dead.invalid/lists/uk_sports.m3u"))?.name)
        assertEquals("panel.example", m3uAccountFromForm(m3uForm("http://panel.example/get.php?username=u&password=p"))?.name)
        assertEquals("Mine", m3uAccountFromForm(m3uForm("http://dead.invalid/lists/uk_sports.m3u", name = "Mine"))?.name)
    }
}
