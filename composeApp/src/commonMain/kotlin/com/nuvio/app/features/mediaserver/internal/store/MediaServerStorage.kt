package com.nuvio.app.features.mediaserver.internal.store

/**
 * Plain (non-secret) persistence of media-server state: the per-profile entry list (addresses and user ids
 * - the same things that sync to the account, never a credential) and the device's pinned certificates
 * (public fingerprints). Mirrors the other feature stores: SharedPreferences on Android, NSUserDefaults on
 * iOS/tvOS, the desktop key-value store on desktop.
 */
internal expect object MediaServerStorage {
    fun loadEntriesJson(profileId: Int): String?
    fun saveEntriesJson(profileId: Int, json: String)
    fun removeEntriesJson(profileId: Int)
    fun loadTrustJson(): String?
    fun saveTrustJson(json: String)
    fun removeTrustJson()
}
