package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SportsDetailStorage {
    private val store = DesktopStorage.store("tuvora_sportsdetail")

    actual fun loadString(key: String): String? = store.getString(key)

    actual fun saveString(key: String, value: String) {
        store.putString(key, value)
    }
}
