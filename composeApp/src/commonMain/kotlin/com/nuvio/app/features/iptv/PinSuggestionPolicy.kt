package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/**
 * Which live channels to offer to pin, from how often this profile played them. Pure: no clock, no
 * storage, no UI, so every rule is unit-tested without the app.
 *
 * Rules (identical on the phone, TV, Apple TV and Desktop apps — change them together):
 *  - a channel qualifies with at least [MIN_PLAYS] plays in the last [WINDOW_MS] (14 days);
 *  - a channel already pinned or favourited never qualifies;
 *  - at most [MAX_SUGGESTIONS] (4) are offered, most played first;
 *  - fewer than [MIN_QUALIFYING] (2) qualifying channels means nothing is offered at all;
 *  - "Not now" snoozes everything for [SNOOZE_MS] (14 days); "Stop suggesting" or the Settings switch
 *    off means never.
 */
object PinSuggestionPolicy {
    const val MIN_PLAYS = 3
    const val MAX_SUGGESTIONS = 4
    const val MIN_QUALIFYING = 2
    const val DAY_MS: Long = 24L * 60 * 60 * 1000
    const val WINDOW_MS: Long = 14 * DAY_MS
    const val SNOOZE_MS: Long = 14 * DAY_MS

    /** One channel and the epoch-ms time of each of its recent plays (device-local). */
    @Serializable
    data class ChannelPlays(val contentId: String, val name: String, val logo: String?, val playTimesMs: List<Long>)

    /** A channel the viewer is offered to pin, with how many plays qualified it. */
    data class Candidate(val contentId: String, val name: String, val logo: String?, val plays: Int)

    /** The viewer's control over the suggestions: on by default, snoozed or off by their choice. */
    data class Prefs(val enabled: Boolean = true, val snoozedUntilMs: Long = 0L)

    /** Plays inside the window ending at [nowMs]; the boundary instant counts. */
    fun playsInWindow(playTimesMs: List<Long>, nowMs: Long): Int =
        playTimesMs.count { it >= nowMs - WINDOW_MS }

    /**
     * The channels to offer now: empty when switched off, snoozed, or fewer than [MIN_QUALIFYING] qualify.
     * [alreadyPinned] is every content id the viewer has pinned or favourited. Ties on plays go to the most
     * recently played channel, then to the id, so the order is stable.
     */
    fun suggest(
        channels: List<ChannelPlays>,
        alreadyPinned: Set<String>,
        prefs: Prefs,
        nowMs: Long,
    ): List<Candidate> {
        if (!prefs.enabled || nowMs < prefs.snoozedUntilMs) return emptyList()
        val qualifying = channels
            .asSequence()
            .filter { it.contentId !in alreadyPinned }
            .map { it to playsInWindow(it.playTimesMs, nowMs) }
            .filter { (_, plays) -> plays >= MIN_PLAYS }
            .sortedWith(
                compareByDescending<Pair<ChannelPlays, Int>> { it.second }
                    .thenByDescending { (c, _) -> c.playTimesMs.maxOrNull() ?: Long.MIN_VALUE }
                    .thenBy { it.first.contentId },
            )
            .toList()
        if (qualifying.size < MIN_QUALIFYING) return emptyList()
        return qualifying.take(MAX_SUGGESTIONS).map { (c, plays) -> Candidate(c.contentId, c.name, c.logo, plays) }
    }

    /** "Not now": nothing is offered again until this instant. */
    fun snoozeUntil(nowMs: Long): Long = nowMs + SNOOZE_MS
}
