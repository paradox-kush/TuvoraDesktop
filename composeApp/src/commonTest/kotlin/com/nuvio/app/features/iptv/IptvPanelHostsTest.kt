package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvPanelHostsTest {
    @AfterTest
    fun tearDown() = IptvPanelHosts.clearNotedForTest()

    @Test
    fun `panel hosts come from accounts backups and noted stream urls`() {
        val xtream = XtreamAccount(id = "a", name = "A", baseUrl = "http://panel.example.com:8080", username = "u", password = "p", backupUrls = listOf("https://backup.example.net"))
        val stalker = XtreamAccount(id = "b", name = "B", baseUrl = "http://stalker.example.net/c", username = "", password = "", sourceType = "stalker")
        assertEquals(setOf("panel.example.com", "backup.example.net", "stalker.example.net"), IptvPanelHosts.hostsOf(listOf(xtream, stalker)), "account hosts")
        assertFalse(IptvPanelHosts.isPanelHost("edge.iptv-cdn.example"), "unknown before a stream is handed out")
        IptvPanelHosts.note("http://edge.iptv-cdn.example:25461/live/u/p/1.ts")
        assertTrue(IptvPanelHosts.isPanelHost("EDGE.iptv-cdn.example"), "host of an IPTV stream URL")
        assertEquals(null, IptvPanelHosts.hostOf(""), "blank")
    }

    @Test
    fun `the noted set is bounded`() {
        repeat(600) { IptvPanelHosts.note("http://h$it.example.com/x") }
        assertFalse(IptvPanelHosts.isPanelHost("h0.example.com"), "oldest evicted")
        assertTrue(IptvPanelHosts.isPanelHost("h599.example.com"), "newest kept")
    }
}
