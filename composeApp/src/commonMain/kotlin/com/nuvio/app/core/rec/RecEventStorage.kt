package com.nuvio.app.core.rec

/**
 * Platform storage for the recommendation logger.
 *
 * Split in two on purpose: the small keys belong in the platform's preference store, but the
 * unsent-event queue can reach ~100 KB and neither SharedPreferences nor NSUserDefaults should
 * be holding a blob that size — it goes to a file.
 *
 * Mirrors [com.nuvio.app.core.sync.SyncClientIdentityStorage]'s expect-object shape, including
 * the Android quirk that the actual must be handed a Context before first use.
 */
internal expect object RecEventStorage {
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
    fun removeString(key: String)
    fun loadBoolean(key: String, default: Boolean): Boolean
    fun saveBoolean(key: String, value: Boolean)
    fun loadLong(key: String, default: Long): Long
    fun saveLong(key: String, value: Long)

    /** The queued-events file, as one string. Null when there is nothing pending. */
    fun loadQueue(): String?

    /** Null clears it. */
    fun saveQueue(contents: String?)
}

/** `mobile-android` / `mobile-ios` — must match the rec_events app CHECK constraint. */
internal expect val recAppIdentifier: String

/** Wall-clock epoch millis. Taken from the platform rather than a stdlib clock whose
 *  availability varies by Kotlin target. */
internal expect fun recNowMillis(): Long
