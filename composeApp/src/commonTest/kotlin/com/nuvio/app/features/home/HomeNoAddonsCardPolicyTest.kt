package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** UX38: Home kept saying "No content yet - add your IPTV playlist" after a playlist was added. */
class HomeNoAddonsCardPolicyTest {
    @Test
    fun storeBuildWithoutAPlaylistAsksForOne() {
        assertEquals(
            HomeNoAddonsCard.AddIptvPlaylist,
            HomeNoAddonsCardPolicy.card(addonsEnabled = false, hasAnyIptvPlaylist = false),
        )
    }

    @Test
    fun storeBuildWithAPlaylistShowsNoCard() {
        assertEquals(
            HomeNoAddonsCard.None,
            HomeNoAddonsCardPolicy.card(addonsEnabled = false, hasAnyIptvPlaylist = true),
        )
    }

    @Test
    fun fullBuildKeepsTheNoActiveAddonsCardEitherWay() {
        assertEquals(
            HomeNoAddonsCard.NoActiveAddons,
            HomeNoAddonsCardPolicy.card(addonsEnabled = true, hasAnyIptvPlaylist = false),
        )
        assertEquals(
            HomeNoAddonsCard.NoActiveAddons,
            HomeNoAddonsCardPolicy.card(addonsEnabled = true, hasAnyIptvPlaylist = true),
        )
    }
}
