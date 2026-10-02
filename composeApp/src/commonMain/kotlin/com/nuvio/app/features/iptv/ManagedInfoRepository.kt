package com.nuvio.app.features.iptv

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a profile's managed-playlist info lives between launches (device-local cache, never synced).
 * Every entry belongs to the user who fetched it: another account never reads it (security L8).
 */
internal interface ManagedInfoStore {
    fun read(userId: String, profileId: Int): Map<String, ManagedInfo>
    fun write(userId: String, profileId: Int, info: Map<String, ManagedInfo>)
}

/** In-memory [ManagedInfoStore] — the test double. */
internal class InMemoryManagedInfoStore : ManagedInfoStore {
    private val data = MutableStateFlow<Map<Pair<String, Int>, Map<String, ManagedInfo>>>(emptyMap())
    override fun read(userId: String, profileId: Int): Map<String, ManagedInfo> = data.value[userId to profileId].orEmpty()
    override fun write(userId: String, profileId: Int, info: Map<String, ManagedInfo>) = data.update { it + ((userId to profileId) to info) }
}

@Serializable
private data class StoredManagedInfo(val userId: String, val items: List<ManagedInfo>)

/** Production store: one JSON object (owner + list) per profile in the IPTV prefs bag. */
internal object PrefsManagedInfoStore : ManagedInfoStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun read(userId: String, profileId: Int): Map<String, ManagedInfo> = runCatching {
        XtreamAccountStorage.loadManagedInfoJson(profileId)
            ?.let { json.decodeFromString(StoredManagedInfo.serializer(), it) }
            ?.takeIf { it.userId == userId }
            ?.items?.associateBy { it.playlistKey }
    }.getOrNull().orEmpty()

    override fun write(userId: String, profileId: Int, info: Map<String, ManagedInfo>) {
        runCatching {
            XtreamAccountStorage.saveManagedInfoJson(
                profileId, json.encodeToString(StoredManagedInfo.serializer(), StoredManagedInfo(userId, info.values.toList())),
            )
        }
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

    /** The signed-in (non-anonymous) user the cache belongs to; null when nobody is. */
    internal var userId: () -> String? = {
        (AuthRepository.state.value as? AuthState.Authenticated)?.takeIf { !it.isAnonymous }?.userId
    }

    private val maps = MutableStateFlow<Map<Int, Map<String, ManagedInfo>>>(emptyMap())

    /** Whose map is in memory; another user (or nobody) starts from an empty one. */
    private var owner: String? = null

    private fun syncOwner() {
        val now = userId()
        if (now != owner) {
            owner = now
            maps.value = emptyMap()
        }
    }

    /** Observed by the UI: (profile id -> playlist key -> info). Profiles load lazily on first read. */
    val state: StateFlow<Map<Int, Map<String, ManagedInfo>>> = maps.asStateFlow()

    fun forProfile(profileId: Int): Map<String, ManagedInfo> {
        syncOwner()
        maps.value[profileId]?.let { return it }
        val user = owner ?: return emptyMap()
        val loaded = store.read(user, profileId)
        maps.update { if (profileId in it) it else it + (profileId to loaded) }
        return maps.value[profileId].orEmpty()
    }

    fun infoFor(profileId: Int, playlistKey: String): ManagedInfo? = forProfile(profileId)[playlistKey]

    fun isManaged(profileId: Int, playlistKey: String): Boolean =
        ManagedPlaylistPolicy.isManaged(playlistKey, forProfile(profileId))

    fun replace(profileId: Int, info: Map<String, ManagedInfo>) {
        if (forProfile(profileId) == info) return
        val user = owner
        maps.update { it + (profileId to info) }
        if (user != null) store.write(user, profileId, info)
    }

    /** Sign-out / account deletion: nothing of the previous account may remain in memory. */
    fun clearLocalState() {
        maps.value = emptyMap()
        owner = null
    }

    /** Test seam: install a map without touching the store. */
    internal fun installForTest(profileId: Int, info: Map<String, ManagedInfo>) {
        syncOwner()
        maps.update { it + (profileId to info) }
    }

    internal fun resetForTest() {
        maps.value = emptyMap()
        owner = null
        store = PrefsManagedInfoStore
        userId = {
            (AuthRepository.state.value as? AuthState.Authenticated)?.takeIf { !it.isAnonymous }?.userId
        }
    }
}
