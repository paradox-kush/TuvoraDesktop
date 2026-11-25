package com.nuvio.app.features.notifications

/**
 * Desktop delivery v1 is in-app only (the Calendar's New episodes card). There is no OS notification here, and this
 * actual does not pretend otherwise: permission is never "granted", nothing is scheduled, and the test button does
 * nothing. A tray notification is a later, separately verified step.
 */
internal actual object EpisodeReleaseNotificationPlatform {
    actual suspend fun notificationsAuthorized(): Boolean = false

    actual suspend fun requestAuthorization(): Boolean = false

    actual suspend fun scheduleEpisodeReleaseNotifications(
        requests: List<EpisodeReleaseNotificationRequest>,
    ) = Unit

    actual suspend fun clearScheduledEpisodeReleaseNotifications() = Unit

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) = Unit

    actual suspend fun recordAppOpened(epochMs: Long) = Unit

    actual fun openNotificationSettings() = Unit
}
