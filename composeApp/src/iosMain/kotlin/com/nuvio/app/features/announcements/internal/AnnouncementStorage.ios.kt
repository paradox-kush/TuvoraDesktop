package com.nuvio.app.features.announcements.internal

import platform.Foundation.NSUserDefaults

internal actual object AnnouncementStorage {
    private const val PREFIX = "tuvora_announcements."

    actual fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(PREFIX + key)

    actual fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFIX + key)
    }
}
