package com.nuvio.app.features.notifications

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object EpisodeReleaseNotificationsStorage {
    private val store = DesktopStorage.store("nuvio_episode_release_notifications")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("episode_release_notifications"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("episode_release_notifications"), payload)
    }

    /** Device-local side files (viewing history, alert bookkeeping), kept per profile and never synced. */
    actual fun loadAux(name: String): String? =
        store.getString(ProfileScopedKey.of("episode_release_notifications_$name"))

    actual fun saveAux(name: String, payload: String) {
        store.putString(ProfileScopedKey.of("episode_release_notifications_$name"), payload)
    }
}
