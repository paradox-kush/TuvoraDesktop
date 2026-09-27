package com.nuvio.app.features.iptv.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F02: custom groups made on the website are listed on devices (they synced but nothing showed them). */
class IptvCustomGroupPolicyTest {

    private fun group(id: String, type: String = "live", playlist: String? = "pl", members: List<String> = listOf("a")) =
        CustomGroup(id, type, playlist, "Group $id", 0, members)

    @Test
    fun a_playlist_shows_its_own_groups_and_those_spanning_playlists_of_the_same_type() {
        val groups = listOf(group("mine"), group("span", playlist = null), group("other", playlist = "pl2"), group("vod", type = "movies"))
        assertEquals(listOf("mine", "span"), IptvCustomGroupPolicy.groupsFor("pl", "live", groups).map { it.id })
    }

    @Test
    fun members_resolve_in_the_group_order_skipping_missing_hidden_and_repeated_ones() {
        val lineup = mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val g = group("g", members = listOf("c", "gone", "a", "b", "c", "d"))
        val shown = IptvCustomGroupPolicy.members(g, lineup, channelOverlay = mapOf("b" to ChannelOverlay(hidden = true)))
        assertEquals(listOf("C", "A", "D"), shown)
    }

    @Test
    fun group_rows_carry_a_prefix_so_they_never_collide_with_provider_category_ids() {
        val row = IptvCustomGroupPolicy.rowId("123")
        assertEquals("123", IptvCustomGroupPolicy.groupIdOf(row))
        assertNull(IptvCustomGroupPolicy.groupIdOf("123"), "a provider category id is not a group row")
    }
}
