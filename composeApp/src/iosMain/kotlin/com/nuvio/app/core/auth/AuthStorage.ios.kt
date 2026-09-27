package com.nuvio.app.core.auth

import platform.Foundation.NSUserDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"

    actual fun loadAnonymousUserId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        NSUserDefaults.standardUserDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }

    actual fun loadValue(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun saveValue(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }

    actual fun removeValue(key: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }
}
