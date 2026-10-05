package com.nuvio.app.features.iptv.epg

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.IptvOverlaySyncAdapter
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F14 — the user's manual "use THIS guide channel for this channel" picks.
 *
 * Stored per profile in the overlay DB (kind "epg", keyed on the channel's canon-v1 entity id, so a
 * pick survives a provider renumber and syncs to every device + the web like hide/pin/rename) and
 * applied at READ time as the ladder's MANUAL rung — the ingested guide is shared by all profiles,
 * the pick is not. The ingest keeps the programmes of every picked guide channel
 * ([IptvOverlayStore.epgOverrideGuideIds]); a pick whose programmes are not on disk yet triggers one
 * forced re-ingest of that playlist (a user action, never a timer).
 */
internal object EpgOverrides {

    private val log = Logger.withTag("EpgOverrides")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var cachedProfile = -1
    private val cache = HashMap<String, Map<String, String>>()

    /** Bumps whenever a pick changes, so an open guide/picker can re-ask. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The active profile's picks for [playlistId]: entity id -> guide channel id. Cached. */
    suspend fun forPlaylist(playlistId: String): Map<String, String> {
        val profile = ProfileRepository.activeProfileId
        mutex.withLock {
            if (cachedProfile != profile) { cache.clear(); cachedProfile = profile }
            cache[playlistId]?.let { return it }
        }
        val loaded = runCatching { IptvOverlayStore.epgOverrides(profile, playlistId) }.getOrDefault(emptyMap())
        mutex.withLock { if (cachedProfile == profile) cache[playlistId] = loaded }
        return loaded
    }

    /** Sets ([guideId] non-null) or clears the pick for one channel, then syncs and re-ingests if needed. */
    fun set(acc: XtreamAccount, entityId: String, guideId: String?, guideName: String?) {
        val profile = ProfileRepository.activeProfileId
        scope.launch {
            runCatching {
                IptvOverlayStore.setEpgOverride(profile, entityId, acc.id, guideId?.let { normalizeChannelId(it) }, guideName, TraktPlatformClock.nowEpochMs())
                invalidate(acc.id)
                // Delta push: only the dirty rows (this one) go up — the 2026-09-19 lesson.
                runCatching { IptvOverlaySyncAdapter.push(profile) }
                if (guideId != null) reingestIfMissing(acc)
            }.onFailure { log.w(it) { "set override failed" } }
        }
    }

    /** Pulled picks from another device / the web landed for these playlists. */
    fun onRemoteChanged(profileId: Int, playlistIds: Set<String>) {
        scope.launch {
            for (id in playlistIds) invalidate(id)
            if (profileId != ProfileRepository.activeProfileId) return@launch
            val accounts = XtreamRepository.uiState.value.accounts
            for (id in playlistIds) accounts.firstOrNull { it.id == id }?.let { runCatching { reingestIfMissing(it) } }
        }
    }

    private suspend fun invalidate(playlistId: String) {
        mutex.withLock { cache.remove(playlistId) }
        _changes.value = _changes.value + 1
        com.nuvio.app.features.iptv.XtreamHubRepository.onGuideDataChanged()
    }

    /**
     * Re-ingests the playlist only when some pick points at a guide channel whose programmes were
     * not kept by the last ingest — the common case (picking a channel the matcher also found for
     * a sibling) costs nothing.
     */
    private suspend fun reingestIfMissing(acc: XtreamAccount) {
        val partition = XmltvClient.partitionOf(acc)
        val now = TraktPlatformClock.nowEpochMs()
        val missing = forPlaylist(acc.id).values.toSet().any { gid ->
            val key = IptvContentDb.epgGuideKeyForGuideId(partition, gid) ?: return@any false
            IptvContentDb.epgAround(partition, key, now, 1).isEmpty()
        }
        if (missing) XmltvClient.ensureEpg(acc, force = true)
    }
}
