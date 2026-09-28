package com.nuvio.app.features.announcements.internal

import android.content.Context
import android.content.SharedPreferences

internal actual object AnnouncementStorage {
    private const val PREFERENCES_NAME = "tuvora_announcements"

    private var preferences: SharedPreferences? = null

    /** Registered as an AndroidStartup task in AndroidFeatureWiring. */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadString(key: String): String? = preferences?.getString(key, null)

    actual fun saveString(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }
}
