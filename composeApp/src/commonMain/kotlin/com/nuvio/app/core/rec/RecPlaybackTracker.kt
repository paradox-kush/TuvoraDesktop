package com.nuvio.app.core.rec

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Bound on remembered playbacks, so a long session cannot grow this without limit. */
private const val MAX_TRACKED_PLAYBACKS = 64

/**
 * Turns the watch-progress stream into the recommendation stream's playback events. Twin of
 * NuvioTV's `RecPlaybackTracker`.
 *
 * WHY THIS MATTERS MORE THAN IMPRESSIONS: the recommender's strongest variant is trained with a
 * like/dislike signal, which MovieLens supplies as star ratings. Tuvora has no ratings — but how
 * far someone watched is the same information. Bailing at 25% is a low rating; reaching 90% is a
 * high one. These four crossings are what let a Tuvora-trained model use the architecture's best
 * lever at all.
 *
 * Derived, never re-measured: percentages come from the position/duration the player already
 * reports, so these events can never disagree with Continue Watching.
 *
 * Monotonic per playback: a bucket fires once, and seeking backwards then forwards does not
 * re-fire it. Leaving the player and returning is a new playback and does.
 *
 * Deliberately decoupled from the app's own playback types — it takes primitives, so the call
 * site in WatchProgressRepository stays a single line and this file has no dependency on the
 * player or watch-progress models.
 */
object RecPlaybackTracker {
    private class PlaybackState {
        var started: Boolean = false
        var highestBucket: Int = 0
        var completed: Boolean = false
    }

    private class Transition(
        val started: Boolean,
        val crossed: List<Int>,
        val completed: Boolean,
    )

    private val lock = SynchronizedObject()
    private val states = LinkedHashMap<String, PlaybackState>()

    /**
     * Call on every progress update. Cheap and idempotent — the vast majority of calls do
     * nothing but compare an integer. Never throws.
     *
     * @param itemId the SHOW's id for an episode, so training can roll up to the show
     */
    fun onProgress(
        itemId: String,
        contentType: String,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
    ) {
        runCatching {
            track(itemId, contentType, season, episode, positionMs, durationMs)
        }
    }

    /** The user left the player: the next play of this item starts a fresh playback. */
    fun onPlaybackEnded(itemId: String, season: Int?, episode: Int?) {
        runCatching {
            synchronized(lock) { states.remove(key(itemId, season, episode)) }
        }
    }

    private fun track(
        itemId: String,
        contentType: String,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L || positionMs < 0L) return
        val percent = ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
        val key = key(itemId, season, episode)

        // The whole decision is taken under the lock and returned, because atomicfu's
        // `synchronized` is not inline enough for deferred assignment to outer vals.
        val transition = synchronized(lock) {
            if (states.size >= MAX_TRACKED_PLAYBACKS && !states.containsKey(key)) {
                states.remove(states.keys.first())
            }
            val state = states.getOrPut(key) { PlaybackState() }

            val start = !state.started
            state.started = true

            val buckets = REC_PROGRESS_BUCKETS.filter { it > state.highestBucket && percent >= it }
            if (buckets.isNotEmpty()) state.highestBucket = buckets.max()

            val complete = !state.completed && percent >= 90
            if (complete) state.completed = true

            Transition(start, buckets, complete)
        }
        val fireStart = transition.started
        val crossed = transition.crossed
        val fireComplete = transition.completed

        if (fireStart) {
            RecEventLogger.log(event(RecEventType.PLAY_START, itemId, contentType, season, episode))
        }
        for (bucket in crossed) {
            RecEventLogger.log(
                event(RecEventType.PLAY_PROGRESS, itemId, contentType, season, episode)
                    .copy(progressPct = bucket)
            )
        }
        if (fireComplete) {
            RecEventLogger.log(
                event(RecEventType.PLAY_COMPLETE, itemId, contentType, season, episode)
            )
        }
    }

    private fun event(
        eventType: String,
        itemId: String,
        contentType: String,
        season: Int?,
        episode: Int?,
    ): RecEvent = RecEvent(
        eventType = eventType,
        // Playback has no shelf behind it by this point; the row context that led here lives on
        // the click event, joinable by (session, item).
        surface = RecSurface.DETAILS,
        contentType = contentType,
        itemId = itemId,
        tmdbId = itemId.removePrefix("tmdb:").takeIf { it != itemId }?.toIntOrNull(),
        season = season,
        episode = episode,
    )

    private fun key(itemId: String, season: Int?, episode: Int?): String =
        "$itemId|${season ?: -1}|${episode ?: -1}"
}

/** Maps the app's content-type strings onto the event stream's closed set. */
fun recContentTypeOf(contentType: String, season: Int?, episode: Int?): String = when {
    season != null || episode != null -> RecContentType.EPISODE
    contentType.equals("series", ignoreCase = true) -> RecContentType.SERIES
    contentType.equals("tv", ignoreCase = true) -> RecContentType.LIVE
    contentType.equals("channel", ignoreCase = true) -> RecContentType.LIVE
    else -> RecContentType.MOVIE
}
