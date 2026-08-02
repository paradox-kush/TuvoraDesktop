package com.nuvio.app.core.rec

import android.content.Context
import android.content.SharedPreferences
import java.io.File

private const val PREFERENCES_NAME = "nuvio_rec_events"
private const val QUEUE_FILE = "rec-events-queue.jsonl"

internal actual object RecEventStorage {
    private var preferences: SharedPreferences? = null
    private var filesDir: File? = null

    /** Called from the Android app's initialisation, alongside SyncClientIdentityStorage. */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        filesDir = context.filesDir
    }

    actual fun loadString(key: String): String? = preferences?.getString(key, null)

    actual fun saveString(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }

    actual fun loadBoolean(key: String, default: Boolean): Boolean =
        preferences?.getBoolean(key, default) ?: default

    actual fun saveBoolean(key: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    actual fun loadLong(key: String, default: Long): Long =
        preferences?.getLong(key, default) ?: default

    actual fun saveLong(key: String, value: Long) {
        preferences?.edit()?.putLong(key, value)?.apply()
    }

    actual fun loadQueue(): String? = runCatching {
        val file = File(filesDir ?: return null, QUEUE_FILE)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    actual fun saveQueue(contents: String?) {
        runCatching {
            val file = File(filesDir ?: return, QUEUE_FILE)
            if (contents == null) file.delete() else file.writeText(contents)
        }
    }
}

internal actual val recAppIdentifier: String = "mobile-android"

internal actual fun recNowMillis(): Long = System.currentTimeMillis()
