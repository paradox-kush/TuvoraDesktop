package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupLinkRoutePolicyTest {
    @Test
    fun signedInOnTheShellOpensThePreviewInPlace() {
        val r = SetupLinkRoutePolicy.decide(isGuest = false, onTabs = true, canPopToTabs = true)
        assertEquals("IptvSetupPreview", r.settingsPage)
        assertEquals(false, r.leaveToTabsFirst, "already on the shell: nothing to pop")
    }

    @Test
    fun guestOnTheShellOpensTheAddPageNotThePreview() {
        val r = SetupLinkRoutePolicy.decide(isGuest = true, onTabs = true, canPopToTabs = true)
        assertEquals("IptvAddPlaylist", r.settingsPage)
        assertEquals(false, r.leaveToTabsFirst)
    }

    @Test
    fun linkOverPlaybackLeavesToTheShellFirst() {
        val r = SetupLinkRoutePolicy.decide(isGuest = false, onTabs = false, canPopToTabs = true)
        assertEquals("IptvSetupPreview", r.settingsPage)
        assertEquals(true, r.leaveToTabsFirst, "the preview lives in the tab shell, hidden behind the player")
    }

    @Test
    fun guestOverPlaybackAlsoLeavesToTheShell() {
        val r = SetupLinkRoutePolicy.decide(isGuest = true, onTabs = false, canPopToTabs = true)
        assertEquals("IptvAddPlaylist", r.settingsPage)
        assertEquals(true, r.leaveToTabsFirst)
    }

    @Test
    fun nativeNavigationNeverPopsFromCommonCode() {
        val r = SetupLinkRoutePolicy.decide(isGuest = false, onTabs = false, canPopToTabs = false)
        assertEquals(false, r.leaveToTabsFirst, "UIKit owns that stack")
    }
}
