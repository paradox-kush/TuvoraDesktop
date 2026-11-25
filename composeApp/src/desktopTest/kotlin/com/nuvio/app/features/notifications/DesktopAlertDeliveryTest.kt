package com.nuvio.app.features.notifications

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Desktop delivery v1 is in-app only (the Calendar's New episodes card). These pin that the platform actual does not
 * pretend otherwise: no OS permission is ever reported as granted, so the shared policy plans nothing for the OS, and
 * the build policy keeps the OS-alert controls (Alert me chip, end-of-episode prompt, the episode alert settings) off.
 * When a real tray notification lands, these are the tests to change on purpose.
 */
class DesktopAlertDeliveryTest {
    private val request = EpisodeReleaseNotificationRequest(
        requestId = "r1",
        notificationTitle = "t",
        notificationBody = "b",
        releaseDateIso = "2026-11-24",
        deepLinkUrl = "tuvora://home",
    )

    @Test
    fun `desktop never reports OS notification permission as granted`() = runTest {
        assertFalse(EpisodeReleaseNotificationPlatform.notificationsAuthorized())
        assertFalse(EpisodeReleaseNotificationPlatform.requestAuthorization())
    }

    @Test
    fun `desktop scheduling clearing and the test notification are honest no-ops`() = runTest {
        EpisodeReleaseNotificationPlatform.scheduleEpisodeReleaseNotifications(listOf(request))
        EpisodeReleaseNotificationPlatform.clearScheduledEpisodeReleaseNotifications()
        EpisodeReleaseNotificationPlatform.showTestNotification(request)
        EpisodeReleaseNotificationPlatform.recordAppOpened(1_000L)
        EpisodeReleaseNotificationPlatform.openNotificationSettings()
    }

    @Test
    fun `desktop build policy keeps the OS alert controls off`() {
        assertEquals(false, AppFeaturePolicy.notificationsEnabled)
    }
}
