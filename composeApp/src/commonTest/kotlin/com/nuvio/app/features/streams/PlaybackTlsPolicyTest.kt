package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTlsPolicyTest {
    private val panels = setOf("panel.example.com", "stalker.example.net", "m3u.cdn.example.org", "192.0.2.10")
    private val mediaServers = setOf("jellyfin.home.example.com", "192.0.2.10")

    private fun classify(host: String?) = PlaybackTlsPolicy.classify(host, { it in panels }, { it in mediaServers })

    @Test
    fun `iptv panel hosts of every source type may fall back`() {
        assertEquals(PlaybackTlsClass.PermissivePanel, classify("panel.example.com"), "xtream panel")
        assertEquals(PlaybackTlsClass.PermissivePanel, classify("stalker.example.net"), "stalker portal")
        assertEquals(PlaybackTlsClass.PermissivePanel, classify("m3u.cdn.example.org"), "m3u stream host")
    }

    @Test
    fun `media servers addons catalogs and unknown hosts stay strict`() {
        assertEquals(PlaybackTlsClass.Strict, classify("jellyfin.home.example.com"), "media server")
        assertEquals(PlaybackTlsClass.Strict, classify("torrentio.strem.fun"), "addon host")
        assertEquals(PlaybackTlsClass.Strict, classify("api.themoviedb.org"), "catalog host")
        assertEquals(PlaybackTlsClass.Strict, classify("cdn.debrid.example"), "cdn")
        assertEquals(PlaybackTlsClass.Strict, classify(null), "no host")
        assertEquals(PlaybackTlsClass.Strict, classify("  "), "blank host")
    }

    @Test
    fun `a media server is never unverified even when it doubles as a panel host`() {
        assertEquals(PlaybackTlsClass.Strict, classify("192.0.2.10"), "server wins")
    }

    @Test
    fun `hosts are matched case and bracket insensitively`() {
        assertEquals(PlaybackTlsClass.PermissivePanel, classify("PANEL.Example.COM."), "case and trailing dot")
        assertEquals("2001:db8::1", PlaybackTlsPolicy.normalize("[2001:DB8::1]"), "ipv6 brackets")
        assertNull(PlaybackTlsPolicy.normalize("."), "nothing left")
    }
}
