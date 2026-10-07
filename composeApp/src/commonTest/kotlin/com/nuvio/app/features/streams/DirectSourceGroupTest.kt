package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Wave 3 / P0: the direct lane groups its single stream under the id THAT STREAM carries (lifted from
 * the resolved item), no longer the "xtream" literal - the id that feeds StreamLinkCacheRepository.save
 * and PlayerLaunch.providerAddonId.
 */
class DirectSourceGroupTest {

    @Test
    fun `the group id is the one the resolved stream carries`() {
        val iptv = StreamItem(name = "Direct", url = "http://p.example/movie/u/p/1.mp4", addonName = "My IPTV", addonId = "xtream")
        val group = iptv.asDirectSourceGroup()
        assertEquals("xtream", group.addonId, "IPTV output is unchanged")
        assertEquals("My IPTV", group.addonName)
        assertEquals(listOf(iptv), group.streams)
        assertFalse(group.isLoading)
    }

    @Test
    fun `a second source gets its own group id without a code change`() {
        val server = StreamItem(name = "Direct", url = "ms-deferred:srv|42|7", addonName = "Home Server", addonId = "ms")
        assertEquals("ms", server.asDirectSourceGroup().addonId)
    }
}
