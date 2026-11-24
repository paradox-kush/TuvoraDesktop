package com.nuvio.app.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Notifications entry and page: open when the build enables notifications OR a Sports alerts section is registered. */
class NotificationsPageVisibilityTest {
    @Test
    fun `desktop has the policy off but the sports alerts section opens the page`() {
        assertEquals(true, notificationsPageVisible(notificationsEnabled = false, sportsAlertsRegistered = true), "visible for alerts")
    }

    @Test
    fun `nothing registered and policy off keeps the page hidden`() {
        assertEquals(false, notificationsPageVisible(notificationsEnabled = false, sportsAlertsRegistered = false), "hidden")
    }

    @Test
    fun `policy on keeps the page whether or not alerts are registered`() {
        assertEquals(true, notificationsPageVisible(true, false), "policy on")
        assertEquals(true, notificationsPageVisible(true, true), "both")
    }

    @Test
    fun `the access object reports registration`() {
        SportsAlertsSettingsSectionAccess.resetForTest()
        assertEquals(false, SportsAlertsSettingsSectionAccess.isRegistered(), "none registered")
        SportsAlertsSettingsSectionAccess.register(object : SportsAlertsSettingsSection {
            override fun androidx.compose.foundation.lazy.LazyListScope.sportsAlertsSettings(isTablet: Boolean) = Unit
        })
        assertEquals(true, SportsAlertsSettingsSectionAccess.isRegistered(), "registered")
        SportsAlertsSettingsSectionAccess.resetForTest()
    }
}
