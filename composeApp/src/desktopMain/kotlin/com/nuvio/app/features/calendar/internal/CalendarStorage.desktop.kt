package com.nuvio.app.features.calendar.internal

import com.nuvio.app.core.storage.DesktopStorage

internal actual object CalendarStorage {
    private val store = DesktopStorage.store("tuvora_calendar")

    actual fun loadString(key: String): String? = store.getString(key)

    actual fun saveString(key: String, value: String) {
        store.putString(key, value)
    }
}
