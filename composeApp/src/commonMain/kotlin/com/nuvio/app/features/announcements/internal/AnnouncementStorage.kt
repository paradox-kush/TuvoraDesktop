package com.nuvio.app.features.announcements.internal

/** Key-value seam so the repository tests without platform storage. */
internal interface AnnouncementStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
}

/**
 * Per-DEVICE storage (not per profile, never synced): the cached list, the last fetch time and the
 * dismissed ids. Android needs a Context before first use (see AndroidFeatureWiring); until then
 * loads return null and saves are dropped, which only costs one extra fetch.
 */
internal expect object AnnouncementStorage {
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
}

internal object PlatformAnnouncementStore : AnnouncementStore {
    override fun load(key: String): String? = AnnouncementStorage.loadString(key)
    override fun save(key: String, value: String) = AnnouncementStorage.saveString(key, value)
}
