package com.nuvio.app.features.iptv.overlay

import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenItem
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvHiddenItemsControllerTest {

    private val account = XtreamAccount(id = "http://h|u", name = "My Provider", baseUrl = "http://h", username = "u", password = "p")
    private val sports = HiddenItem(HiddenKind.GROUP, "live", "c:v1:sports", "Sports")
    private val bbc = HiddenItem(HiddenKind.CHANNEL, "live", "fp:v1:bbc", "BBC One")

    private class FakeSource(var items: List<HiddenItem>) : IptvHiddenItemsSource {
        val unhidden = mutableListOf<HiddenItem>()
        val hiddenGroups = mutableListOf<Pair<String, String>>()
        override suspend fun load(account: XtreamAccount) = items
        override fun unhide(account: XtreamAccount, item: HiddenItem) { unhidden += item }
        override fun hideGroup(account: XtreamAccount, contentType: String, categoryName: String) { hiddenGroups += contentType to categoryName }
    }

    @Test
    fun opening_loads_the_playlists_hidden_items() = runTest {
        val source = FakeSource(listOf(sports, bbc))
        val controller = IptvHiddenItemsController(TestScope(StandardTestDispatcher(testScheduler)), source)
        controller.open(account)
        assertTrue(controller.state.value.loading, "loading until the catalog is read")
        advanceUntilIdle()
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(sports, bbc), controller.state.value.items)
    }

    @Test
    fun unhiding_writes_the_overlay_and_drops_the_row_at_once() = runTest {
        val source = FakeSource(listOf(sports, bbc))
        val controller = IptvHiddenItemsController(TestScope(StandardTestDispatcher(testScheduler)), source)
        controller.open(account)
        advanceUntilIdle()
        controller.unhide(account, sports)
        assertEquals(listOf(sports), source.unhidden)
        assertEquals(listOf(bbc), controller.state.value.items)
    }

    @Test
    fun hiding_a_group_goes_through_the_source() {
        val source = FakeSource(emptyList())
        val controller = IptvHiddenItemsController(TestScope(), source)
        controller.hideGroup(account, "movies", "Horror")
        assertEquals(listOf("movies" to "Horror"), source.hiddenGroups)
    }
}
