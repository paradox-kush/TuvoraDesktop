package com.nuvio.app.features.mediaserver.internal.policy

import kotlin.math.abs

/**
 * When a playback session tells its server what is happening (design 5.7). Lifecycle-bound to the player
 * (the reporter calls it; it never schedules anything) and cheap by construction:
 *  - start: one report;
 *  - playing: a progress report every [PLAYING_INTERVAL_MS], plus one right after a seek lands or a resume;
 *  - pause: one `IsPaused=true` report, then a check-in every [PAUSED_CHECK_IN_MS] (Jellyfin reaps a session
 *    silent for 5 minutes - the check-in keeps it alive);
 * The cadence is the reference clients' (J2 reference check): ~15 s while playing, ~60 s while paused - the player
 * feeds this from a local 5 s tick (a paused player emits no events of its own).
 *  - stop: always, exactly once.
 * Pure state machine: feed events with a clock reading, get back the report to send (or none).
 */
internal object ProgressReportPolicy {
    const val PLAYING_INTERVAL_MS = 15_000L
    const val PAUSED_CHECK_IN_MS = 60_000L
    /** A position this far off where uninterrupted playback would be is a seek. */
    const val SEEK_JUMP_MS = 5_000L

    data class State(
        val started: Boolean = false,
        val stopped: Boolean = false,
        val lastSentAtMs: Long = 0L,
        val lastSentPositionMs: Long = 0L,
        val lastSentPaused: Boolean = false,
    )

    enum class Kind { START, PROGRESS, STOP }

    data class Report(val kind: Kind, val positionMs: Long, val paused: Boolean)

    data class Step(val state: State, val report: Report?)

    fun start(state: State, nowMs: Long, positionMs: Long): Step {
        if (state.started) return Step(state, null) // a restart of an already-started session is a progress event, not a second START
        return Step(State(started = true, lastSentAtMs = nowMs, lastSentPositionMs = positionMs), Report(Kind.START, positionMs, paused = false))
    }

    fun progress(state: State, nowMs: Long, positionMs: Long, paused: Boolean): Step {
        if (state.stopped) return Step(state, null)
        if (!state.started) return start(state, nowMs, positionMs) // progress before a start is the start (a pause edge follows on the next event)
        val elapsed = nowMs - state.lastSentAtMs
        val send = when {
            paused != state.lastSentPaused -> true // pause / resume edge: say so right away
            paused -> elapsed >= PAUSED_CHECK_IN_MS
            elapsed >= PLAYING_INTERVAL_MS -> true
            else -> abs(positionMs - (state.lastSentPositionMs + elapsed)) > SEEK_JUMP_MS // a seek landed
        }
        if (!send) return Step(state, null)
        return Step(
            state.copy(lastSentAtMs = nowMs, lastSentPositionMs = positionMs, lastSentPaused = paused),
            Report(Kind.PROGRESS, positionMs, paused),
        )
    }

    fun stop(state: State, nowMs: Long, positionMs: Long): Step {
        if (state.stopped) return Step(state, null)
        return Step(state.copy(stopped = true, started = true, lastSentAtMs = nowMs, lastSentPositionMs = positionMs), Report(Kind.STOP, positionMs, paused = false))
    }

    /** The server's own played threshold (`MaxResumePct`, default 90): Jellyfin/Emby mark an item played on a Stopped report at or beyond it. */
    const val SERVER_PLAYED_THRESHOLD_PERCENT = 90

    /**
     * No double-scrobble (design 5.7): an explicit "mark played" call is only needed when Tuvora considers the
     * item finished but the stop report did NOT cross the server's threshold - otherwise the server already
     * marked it from the Stopped report, and a second mark would also fire the server's own Trakt plugin twice.
     */
    fun shouldMarkPlayedExplicitly(tuvoraConsidersFinished: Boolean, stopPositionMs: Long, durationMs: Long): Boolean {
        if (!tuvoraConsidersFinished) return false
        if (durationMs <= 0) return true // cannot tell whether the server crossed its threshold; the explicit mark is idempotent
        val percent = stopPositionMs * 100.0 / durationMs
        return percent < SERVER_PLAYED_THRESHOLD_PERCENT
    }
}
