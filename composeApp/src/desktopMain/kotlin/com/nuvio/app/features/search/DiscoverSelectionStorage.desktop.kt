package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DiscoverSelectionStorage {
    private const val catalogKey = "discover_catalog_key"

    private val store = DesktopStorage.store("nuvio_discover_selection")

    actual fun loadCatalogKey(): String? = store.getString(ProfileScopedKey.of(catalogKey))

    actual fun saveCatalogKey(catalogKey: String) {
        store.putString(ProfileScopedKey.of(this.catalogKey), catalogKey)
    }
}
