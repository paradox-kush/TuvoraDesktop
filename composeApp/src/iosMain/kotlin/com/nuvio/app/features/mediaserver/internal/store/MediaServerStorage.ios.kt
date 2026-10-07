package com.nuvio.app.features.mediaserver.internal.store

import platform.Foundation.NSUserDefaults

internal actual object MediaServerStorage {
    private const val entriesKey = "mediaserver_entries"
    private const val trustKey = "mediaserver_trust"

    actual fun loadEntriesJson(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${entriesKey}_$profileId")

    actual fun saveEntriesJson(profileId: Int, json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = "${entriesKey}_$profileId")
    }

    actual fun removeEntriesJson(profileId: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey("${entriesKey}_$profileId")
    }

    actual fun loadTrustJson(): String? = NSUserDefaults.standardUserDefaults.stringForKey(trustKey)

    actual fun saveTrustJson(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = trustKey)
    }

    actual fun removeTrustJson() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(trustKey)
    }
}
