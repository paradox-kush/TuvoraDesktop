package com.nuvio.app.features.mediaserver.internal.store

import android.content.Context
import android.content.SharedPreferences

internal actual object MediaServerStorage {
    private const val preferencesName = "nuvio_mediaserver"
    private const val entriesKey = "entries"
    private const val trustKey = "trust"

    private var preferences: SharedPreferences? = null

    /** Called once at app startup (AndroidFeatureWiring task). */
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEntriesJson(profileId: Int): String? = preferences?.getString("${entriesKey}_$profileId", null)

    actual fun saveEntriesJson(profileId: Int, json: String) {
        val prefs = preferences ?: error("media-server storage not initialised")
        // commit(): a failed write must be visible to the caller (no false "saved"), unlike apply().
        check(prefs.edit().putString("${entriesKey}_$profileId", json).commit()) { "could not save media-server entries" }
    }

    actual fun removeEntriesJson(profileId: Int) {
        preferences?.edit()?.remove("${entriesKey}_$profileId")?.apply()
    }

    actual fun loadTrustJson(): String? = preferences?.getString(trustKey, null)

    actual fun saveTrustJson(json: String) {
        val prefs = preferences ?: error("media-server storage not initialised")
        check(prefs.edit().putString(trustKey, json).commit()) { "could not save pinned certificates" }
    }

    actual fun removeTrustJson() {
        preferences?.edit()?.remove(trustKey)?.apply()
    }
}
