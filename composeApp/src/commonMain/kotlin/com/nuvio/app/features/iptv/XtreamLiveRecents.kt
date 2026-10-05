package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LiveChannelNames
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class XtreamLiveRecent(
    val contentId: String,
    val name: String,
    val logo: String?,
)

/**
 * Recently-watched live channels, profile-scoped + persisted. Live playback records no watch
 * progress (no duration), so this backs the "Live TV" row of Continue Watching. LRU, newest first.
 */
object XtreamLiveRecents {
    private const val CAP = 20
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _recents = MutableStateFlow<List<XtreamLiveRecent>>(emptyList())
    val recents: StateFlow<List<XtreamLiveRecent>> = _recents.asStateFlow()

    private var loaded = false
    private var currentProfileId = 1

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        currentProfileId = ProfileRepository.activeProfileId
        _recents.value = parse(XtreamAccountStorage.loadRecentsJson(currentProfileId))
    }

    fun record(contentId: String, name: String, logo: String?) {
        ensureLoaded()
        val updated = recorded(_recents.value, XtreamLiveRecent(contentId, name, logo), CAP)
        _recents.value = updated
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(updated))
    }

    /** Drops one channel from the row — the Live TV counterpart of removing a Continue Watching card. */
    fun remove(contentId: String) {
        ensureLoaded()
        val updated = _recents.value.filterNot { it.contentId == contentId }
        if (updated.size == _recents.value.size) return
        _recents.value = updated
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(updated))
    }

    /** Empties the row for this profile. */
    fun clear() {
        ensureLoaded()
        if (_recents.value.isEmpty()) return
        _recents.value = emptyList()
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(emptyList<XtreamLiveRecent>()))
    }

    /**
     * Account wipe: drops the in-memory row so the signed-out account's channels don't linger on
     * Home. The persisted JSON goes with the rest of the profile storage.
     */
    fun clearLocalState() {
        loaded = false
        currentProfileId = 1
        _recents.value = emptyList()
    }

    /**
     * IPTV playlist edit: rewrites recent channels under an old `xtream:{accountId}:` id
     * prefix to the new one, or drops them when newPrefix is null (different playlist).
     */
    fun migrateIdPrefix(oldPrefix: String, newPrefix: String?) {
        ensureLoaded()
        if (_recents.value.none { it.contentId.startsWith(oldPrefix) }) return
        val updated = _recents.value.mapNotNull { recent ->
            when {
                !recent.contentId.startsWith(oldPrefix) -> recent
                newPrefix == null -> null
                else -> recent.copy(contentId = newPrefix + recent.contentId.removePrefix(oldPrefix))
            }
        }
        _recents.value = updated
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(updated))
    }

    /** B64: re-keys recent channels in place ([rewrite] = new content id, or null to keep). */
    fun rekeyIds(rewrite: (String) -> String?): Int {
        ensureLoaded()
        val (updated, moved) = rekeyed(_recents.value, rewrite)
        if (moved == 0) return 0
        _recents.value = updated
        XtreamAccountStorage.saveRecentsJson(currentProfileId, json.encodeToString(_recents.value))
        return moved
    }

    /**
     * Pure: [current] with [entry] played now (front of the LRU, capped at [cap]). A launch titled with
     * the generic fallback keeps the name (and logo) the row already had — device pass T3.
     */
    internal fun recorded(current: List<XtreamLiveRecent>, entry: XtreamLiveRecent, cap: Int): List<XtreamLiveRecent> {
        val prior = current.firstOrNull { it.contentId == entry.contentId }
        val kept = if (prior == null) entry else entry.copy(
            name = LiveChannelNames.best(entry.name, prior.name) ?: entry.name,
            logo = entry.logo ?: prior.logo,
        )
        return (listOf(kept) + current.filterNot { it.contentId == entry.contentId }).take(cap)
    }

    /**
     * Pure: [current] re-keyed by [rewrite] (null = keep), one row per id at its newest position; with
     * how many moved. Rows merging onto one id keep the first REAL name (not the newest placeholder).
     */
    internal fun rekeyed(current: List<XtreamLiveRecent>, rewrite: (String) -> String?): Pair<List<XtreamLiveRecent>, Int> {
        var moved = 0
        val merged = LinkedHashMap<String, XtreamLiveRecent>()
        for (recent in current) {
            val row = rewrite(recent.contentId)?.let { moved++; recent.copy(contentId = it) } ?: recent
            val first = merged[row.contentId]
            merged[row.contentId] = if (first == null) row else first.copy(
                name = LiveChannelNames.best(first.name, row.name) ?: first.name,
                logo = first.logo ?: row.logo,
            )
        }
        return merged.values.toList() to moved
    }

    /** Reload this profile's recents on a profile switch (the Home Live TV row observes them live). */
    fun onProfileChanged(profileId: Int) {
        loaded = true
        currentProfileId = profileId
        _recents.value = parse(XtreamAccountStorage.loadRecentsJson(profileId))
    }

    private fun parse(stored: String?): List<XtreamLiveRecent> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<XtreamLiveRecent>>(stored) }.getOrDefault(emptyList())
    }
}

fun XtreamLiveRecent.toMetaPreview(): MetaPreview = MetaPreview(
    id = contentId,
    type = "tv",
    name = name,
    poster = logo,
    logo = logo,
    posterShape = PosterShape.Landscape,
)
