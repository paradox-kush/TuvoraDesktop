package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Own store file. Its name is in [DesktopStorage.PRESERVED_STORE_NAMES], so PlatformLocalAccountDataCleaner
 * (DesktopStorage.wipe) leaves it alone: a guest who signs in is wiped, and the claim is what carries their
 * redeemed playlists into the account.
 */
internal actual object PendingDeviceClaimStorage {
    const val STORE_NAME = "tuvora_device_setup_claims"
    private const val KEY = "pending_claims"

    private val realStore by lazy { DesktopStorage.store(STORE_NAME) }

    /** Test seam: a store on a temp file, so a test never touches the real app data. */
    internal var storeOverrideForTests: DesktopStorage.Store? = null

    private val store: DesktopStorage.Store get() = storeOverrideForTests ?: realStore

    actual fun load(): String? = store.getString(KEY)

    // Written through to the properties file before returning (Store.putString persists synchronously).
    actual fun save(json: String?) {
        store.putString(KEY, json)
    }
}
