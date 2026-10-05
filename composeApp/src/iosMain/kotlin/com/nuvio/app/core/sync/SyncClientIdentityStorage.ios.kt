package com.nuvio.app.core.sync

import platform.Foundation.NSUserDefaults

actual object SyncClientIdentityStorage {
    private const val clientIdKey = "client_instance_id"

    actual fun loadClientId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(clientIdKey)

    actual fun saveClientId(clientId: String) {
        NSUserDefaults.standardUserDefaults.setObject(clientId, forKey = clientIdKey)
    }

    actual fun loadValue(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("nuvio_sync_$key")

    actual fun saveValue(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = "nuvio_sync_$key")
    }
}
