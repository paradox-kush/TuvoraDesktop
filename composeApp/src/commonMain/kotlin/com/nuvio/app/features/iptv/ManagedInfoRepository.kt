package com.nuvio.app.features.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where a profile's managed-playlist info lives between launches (device-local cache, never synced). */
internal interface ManagedInfoStore {
    fun read(profileId: Int): Map<String, ManagedInfo>
    fun write(profileId: Int, info: Map<String, ManagedInfo>)
}

/** In-memory [ManagedInfoStore] — the test double. */
internal class InMemoryManagedInfoStore : ManagedInfoStore {
    private val data = MutableStateFlow<Map<Int, Map<String, ManagedInfo>>>(emptyMap())
    override fun read(profileId: Int): Map<String, ManagedInfo> = data.value[profileId].orEmpty()
    override fun write(profileId: Int, info: Map<String, ManagedInfo>) = data.update { it + (profileId to info) }
}

/** Production store: one JSON list per profile in the IPTV prefs bag. */
internal object PrefsManagedInfoStore : ManagedInfoStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ManagedInfo.serializer())

    override fun read(profileId: Int): Map<String, ManagedInfo> = runCatching {
        XtreamAccountStorage.loadManagedInfoJson(profileId)
            ?.let { json.decodeFromString(serializer, it) }
            ?.associateBy { it.playlistKey }
    }.getOrNull().orEmpty()

    override fun write(profileId: Int, info: Map<String, ManagedInfo>) {
        runCatching { XtreamAccountStorage.saveManagedInfoJson(profileId, json.encodeToString(serializer, info.values.toList())) }
    }
}

/**
 * The managed-playlist map per profile, keyed by playlist key. A playlist is managed iff its key is in
 * the map. Filled by [ManagedInfoRefresher] after a pull that returned playlists, by redeem and by
 * detach — never on a timer — and cached on the device so an offline cold start still protects a managed
 * playlist from a silent detach ([ManagedEditPolicy]).
 */
internal object ManagedInfoRepository {
    internal var store: ManagedInfoStore = PrefsManagedInfoStore

    private val maps = MutableStateFlow<Map<Int, Map<String, ManagedInfo>>>(emptyMap())

    /** Observed by the UI: (profile id -> playlist key -> info). Profiles load lazily on first read. */
    val state: StateFlow<Map<Int, Map<String, ManagedInfo>>> = maps.asStateFlow()

    fun forProfile(profileId: Int): Map<String, ManagedInfo> {
        maps.value[profileId]?.let { return it }
        val loaded = store.read(profileId)
        maps.update { if (profileId in it) it else it + (profileId to loaded) }
        return maps.value[profileId].orEmpty()
    }

    fun infoFor(profileId: Int, playlistKey: String): ManagedInfo? = forProfile(profileId)[playlistKey]

    fun isManaged(profileId: Int, playlistKey: String): Boolean =
        ManagedPlaylistPolicy.isManaged(playlistKey, forProfile(profileId))

    fun replace(profileId: Int, info: Map<String, ManagedInfo>) {
        if (forProfile(profileId) == info) return
        maps.update { it + (profileId to info) }
        store.write(profileId, info)
    }

    /** Sign-out / account deletion: nothing of the previous account may remain in memory. */
    fun clearLocalState() {
        maps.value = emptyMap()
    }

    /** Test seam: install a map without touching the store. */
    internal fun installForTest(profileId: Int, info: Map<String, ManagedInfo>) {
        maps.update { it + (profileId to info) }
    }

    internal fun resetForTest() {
        maps.value = emptyMap()
        store = PrefsManagedInfoStore
    }
}
