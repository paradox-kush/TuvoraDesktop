package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.PinSuggestionPolicy.ChannelPlays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the pin suggestions keep, per profile, ON THIS DEVICE ONLY: play times per live channel,
 * the on/off switch and the snooze. It rides the local prefs bag ([XtreamAccountStorage]) and is never
 * part of any sync payload, telemetry event or backup.
 */
@Serializable
data class LivePinSuggestionState(
    val channels: List<ChannelPlays> = emptyList(),
    val enabled: Boolean = true,
    val snoozedUntilMs: Long = 0L,
) {
    val prefs: PinSuggestionPolicy.Prefs get() = PinSuggestionPolicy.Prefs(enabled, snoozedUntilMs)
}

/** Where the state is kept; a fake in tests, [XtreamAccountStorage] in the app. */
interface LivePinSuggestionPersistence {
    fun load(profileId: Int): String?
    fun save(profileId: Int, json: String)
}

/** The pure reducers behind [LivePinSuggestionStore]: keep the data small and capped. */
object LivePlayCounts {
    /** Most channels kept; the least recently played is dropped first. */
    const val MAX_CHANNELS = 100

    /** Most play times kept per channel (newest). Three qualify a channel; the rest only rank it. */
    const val MAX_PLAYS_PER_CHANNEL = 20

    /**
     * [current] with one play of [contentId] at [nowMs]: plays older than the 14-day window are forgotten
     * (they can no longer count), a placeholder name never replaces a real one, and the list is capped at
     * [MAX_CHANNELS] most-recently-played channels.
     */
    fun recorded(current: List<ChannelPlays>, contentId: String, name: String, logo: String?, nowMs: Long): List<ChannelPlays> {
        val prior = current.firstOrNull { it.contentId == contentId }
        val cutoff = nowMs - PinSuggestionPolicy.WINDOW_MS
        val priorTimes = prior?.playTimesMs.orEmpty()
        // Within 10 minutes of this channel's previous recorded play = the same play, not a new one.
        val samePlay = priorTimes.lastOrNull()?.let { nowMs - it < PinSuggestionPolicy.SAME_PLAY_MS } == true
        val times = (if (samePlay) priorTimes else priorTimes + nowMs).filter { it >= cutoff }.takeLast(MAX_PLAYS_PER_CHANNEL)
        val entry = ChannelPlays(
            contentId = contentId,
            name = com.nuvio.app.core.contracts.LiveChannelNames.best(name, prior?.name) ?: name,
            logo = logo ?: prior?.logo,
            playTimesMs = times,
        )
        val others = current.filterNot { it.contentId == contentId }
            .map { c -> c.copy(playTimesMs = c.playTimesMs.filter { it >= cutoff }) }
            .filter { it.playTimesMs.isNotEmpty() }
        return (listOf(entry) + others)
            .sortedByDescending { it.playTimesMs.maxOrNull() ?: Long.MIN_VALUE }
            .take(MAX_CHANNELS)
    }

    /** Playlist edit: re-key (or, with a null [newPrefix], drop) channels under an old id prefix. */
    fun migrated(current: List<ChannelPlays>, oldPrefix: String, newPrefix: String?): List<ChannelPlays> =
        current.mapNotNull { c ->
            when {
                !c.contentId.startsWith(oldPrefix) -> c
                newPrefix == null -> null
                else -> c.copy(contentId = newPrefix + c.contentId.removePrefix(oldPrefix))
            }
        }
}

/**
 * Play counts + preferences for pin suggestions. Recording is skipped while the viewer has the feature
 * switched off. [profileId] and [nowMs] are injected so tests drive them.
 */
class LivePinSuggestionStore(
    private val persistence: LivePinSuggestionPersistence,
    private val nowMs: () -> Long,
    private val profileId: () -> Int,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(LivePinSuggestionState())
    val state: StateFlow<LivePinSuggestionState> = _state.asStateFlow()

    private var loadedFor: Int? = null

    fun ensureLoaded() {
        val id = profileId()
        if (loadedFor == id) return
        loadedFor = id
        _state.value = parse(persistence.load(id))
    }

    fun now(): Long = nowMs()

    /** One successful live tune. No-op while suggestions are off. */
    fun recordPlay(contentId: String, name: String, logo: String?) {
        ensureLoaded()
        val current = _state.value
        if (!current.enabled) return
        commit(current.copy(channels = LivePlayCounts.recorded(current.channels, contentId, name, logo, nowMs())))
    }

    /** "Clear play counts": forgets the counts; the switch and the snooze stay as the viewer set them. */
    fun clearPlayCounts() {
        ensureLoaded()
        if (_state.value.channels.isEmpty()) return
        commit(_state.value.copy(channels = emptyList()))
    }

    /** The Settings switch. Off is "never": nothing is offered and no more plays are counted. */
    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_state.value.enabled == enabled) return
        commit(_state.value.copy(enabled = enabled))
    }

    /** "Not now": nothing is offered for 14 days. */
    fun snooze() {
        ensureLoaded()
        commit(_state.value.copy(snoozedUntilMs = PinSuggestionPolicy.snoozeUntil(nowMs())))
    }

    /** Playlist edit: keep play counts with the channels' new ids (or drop them with the playlist). */
    fun migrateIdPrefix(oldPrefix: String, newPrefix: String?) {
        ensureLoaded()
        val migrated = LivePlayCounts.migrated(_state.value.channels, oldPrefix, newPrefix)
        if (migrated != _state.value.channels) commit(_state.value.copy(channels = migrated))
    }

    /** Profile switch: load the new profile's own counts and switch. */
    fun onProfileChanged() {
        loadedFor = null
        ensureLoaded()
    }

    /** Account wipe: forget the in-memory state; the persisted blob goes with the profile storage. */
    fun clearLocalState() {
        loadedFor = null
        _state.value = LivePinSuggestionState()
    }

    private fun commit(next: LivePinSuggestionState) {
        _state.value = next
        persistence.save(loadedFor ?: profileId(), json.encodeToString(LivePinSuggestionState.serializer(), next))
    }

    private fun parse(stored: String?): LivePinSuggestionState {
        if (stored.isNullOrBlank()) return LivePinSuggestionState()
        return runCatching { json.decodeFromString(LivePinSuggestionState.serializer(), stored) }
            .getOrDefault(LivePinSuggestionState())
    }
}
