package com.nuvio.app.features.announcements.internal

import com.nuvio.app.core.storage.DesktopStorage

internal actual object AnnouncementStorage {
    private val store = DesktopStorage.store("tuvora_announcements")

    actual fun loadString(key: String): String? = store.getString(key)

    actual fun saveString(key: String, value: String) {
        store.putString(key, value)
    }
}
