package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistAddressTest {
    @Test
    fun `only the host is shown and never credentials or a query`() {
        assertEquals("panel.example.com:8080", PlaylistAddress.hostOnly("http://panel.example.com:8080"))
        assertEquals("cdn.example.com", PlaylistAddress.hostOnly("http://cdn.example.com/get.php?username=u&password=secret&type=m3u"))
        assertEquals("host.example.com", PlaylistAddress.hostOnly("http://user:pw@host.example.com/x"))
        assertEquals("bare.example.com", PlaylistAddress.hostOnly("bare.example.com/path"))
        assertNull(PlaylistAddress.hostOnly(""))
    }

    @Test
    fun `an ISO timestamp reads as its date`() {
        assertEquals("2026-10-01", PlaylistAddress.isoDate("2026-10-01T10:00:00Z"))
        assertEquals("2026-10-01", PlaylistAddress.isoDate("2026-10-01"))
        assertNull(PlaylistAddress.isoDate("yesterday"))
        assertNull(PlaylistAddress.isoDate(null))
    }
}
