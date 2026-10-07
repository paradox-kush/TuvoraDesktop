package com.nuvio.app.features.mediaserver.internal.store

import com.nuvio.app.core.storage.DesktopStorage

internal actual object MediaServerStorage {
    private val store by lazy { DesktopStorage.store("nuvio_mediaserver") }

    actual fun loadEntriesJson(profileId: Int): String? = store.getString("entries_$profileId")

    actual fun saveEntriesJson(profileId: Int, json: String) {
        store.putString("entries_$profileId", json)
    }

    actual fun removeEntriesJson(profileId: Int) {
        store.putString("entries_$profileId", null)
    }

    actual fun loadTrustJson(): String? = store.getString("trust")

    actual fun saveTrustJson(json: String) {
        store.putString("trust", json)
    }

    actual fun removeTrustJson() {
        store.putString("trust", null)
    }
}
