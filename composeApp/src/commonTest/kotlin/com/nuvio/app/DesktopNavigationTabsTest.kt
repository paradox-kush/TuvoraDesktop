package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopNavigationTabsTest {

    @Test
    fun every_root_tab_is_on_the_desktop_nav_surfaces() {
        // B75: Desktop 0.4.x lost IPTV and Sports from its sidebar and top bar after the upstream
        // app-shell merge. Both surfaces iterate DesktopNavigationTabs, so it must hold every tab.
        assertEquals(AppScreenTab.entries.toSet(), DesktopNavigationTabs.toSet(), "missing root tabs on desktop")
        assertEquals(DesktopNavigationTabs.distinct(), DesktopNavigationTabs, "no duplicate tabs")
    }

    @Test
    fun fork_tabs_sit_after_library_as_before_the_merge() {
        assertEquals(
            listOf(AppScreenTab.Home, AppScreenTab.Search, AppScreenTab.Library, AppScreenTab.Iptv, AppScreenTab.Sports, AppScreenTab.Settings),
            DesktopNavigationTabs,
        )
    }
}
