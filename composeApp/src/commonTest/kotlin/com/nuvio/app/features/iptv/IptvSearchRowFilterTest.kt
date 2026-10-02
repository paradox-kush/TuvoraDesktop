package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UX44: provider heading/divider rows ("==== Sky Germany ====") never surface as search hits. */
class IptvSearchRowFilterTest {

    @Test
    fun a_name_framed_by_decoration_runs_is_a_divider() {
        listOf(
            "==== Sky Germany ====",
            "##### UK SPORTS #####",
            "----- Movies -----",
            "*** DE ***",
            "#### 4K ####",
            "====Sky Germany====",
            "== A ==",
            "==== Sky Germany ----",
            "  --- 24/7 ---  ",
            "=== |DE| Sky Cinema ===",
        ).forEach { assertTrue(IptvSearchRowFilter.isDivider(it), "expected divider: '$it'") }
    }

    @Test
    fun a_name_made_only_of_decoration_is_a_divider() {
        listOf("==========", "**", "#### ####", "-----").forEach {
            assertTrue(IptvSearchRowFilter.isDivider(it), "expected divider: '$it'")
        }
    }

    @Test
    fun real_channels_and_titles_are_never_dividers() {
        listOf(
            "4K | Sky Sports",
            "#1 Movie",
            "Sky Sports F1",
            "M*A*S*H",
            "*batteries not included",
            "Sky Sports -- HD",
            "-- Sky News",
            "Sky News --",
            "## Sky Cinema",
            "= Sky =",
            "#Hashtag#",
            "1 = 2",
            "The #### Show",
            "Movie (2019) **",
            "-",
            "",
            "   ",
        ).forEach { assertFalse(IptvSearchRowFilter.isDivider(it), "expected a real row: '$it'") }
    }

    @Test
    fun withoutDividers_keeps_real_rows_in_order() {
        val rows = listOf("==== Sky Germany ====", "Sky Sport 1", "#1 Movie", "----- UK -----", "4K | Sky Sports")
        assertEquals(
            listOf("Sky Sport 1", "#1 Movie", "4K | Sky Sports"),
            IptvSearchRowFilter.withoutDividers(rows) { it },
            "dividers dropped, real rows kept in playlist order",
        )
    }

    @Test
    fun channel_search_never_returns_a_divider_row() {
        val channels = listOf("==== Sky Germany ====", "DE: Sky Sport 1", "DE: Sky Cinema")
        assertEquals(
            listOf("DE: Sky Sport 1", "DE: Sky Cinema"),
            IptvChannelSearchPolicy.search(channels, "sky") { it },
            "the 'Sky Germany' heading matches the words but is not a channel",
        )
    }
}
