package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.pause.LivePauseState
import com.nuvio.app.features.player.PlayerControlsAction

/**
 * Desktop draws its player controls in a native web layer ON TOP of Compose, so Live pause / rewind reaches the
 * viewer through that layer: this object is the pure translation between the pause state and the native bar
 * ([rewind]) and between the native layer's presses and the pause controller ([forEvent], [forAction],
 * [forScrubFinish]). The Composable only executes the [Intent].
 */
internal object LivePauseNativeControls {
    /** The native "-mm:ss - LIVE" chip sends this event; it means Go live. */
    const val GO_LIVE_EVENT = "liveGoLive"

    /** One Left / Right press. */
    const val SEEK_STEP_MS = 10_000L

    /** The bar's bounds move in steps this size: the native controls are re-sent only when they change. */
    private const val QUANTUM_MS = 5_000L

    /** Shorter than this there is nothing worth scrubbing. */
    private const val MIN_WINDOW_MS = 5_000L

    /** What the native seek bar shows: [active] = draw the rewind bar between [startMs] and [endMs] (mpv's clock). */
    data class Rewind(val active: Boolean, val startMs: Long, val endMs: Long) {
        companion object { val NONE = Rewind(false, 0L, 0L) }
    }

    fun rewind(state: LivePauseState): Rewind {
        val s = state as? LivePauseState.Shifted ?: return Rewind.NONE
        if (s.windowEndMs - s.windowStartMs < MIN_WINDOW_MS) return Rewind.NONE
        // Inward: never offer a second the cache may not hold yet, and keep the pushed value stable between ticks.
        val start = ceilTo(s.windowStartMs)
        val end = (s.windowEndMs / QUANTUM_MS) * QUANTUM_MS
        return if (end - start >= MIN_WINDOW_MS) Rewind(true, start, end) else Rewind(true, s.windowStartMs, s.windowEndMs)
    }

    sealed interface Intent {
        data object Play : Intent
        data object Pause : Intent
        data object Toggle : Intent
        data object GoLive : Intent
        data class Seek(val positionMs: Long) : Intent

        /** Not ours: the caller falls through to the engine's own handling. */
        data object Unhandled : Intent
    }

    fun forEvent(type: String, value: Double, state: LivePauseState): Intent = when (type) {
        // The native Space / button toggle names its target (1 = play, 0 = pause).
        "setPlaybackState", "setPlaybackStateQuiet" -> if (value >= 0.5) Intent.Play else Intent.Pause
        GO_LIVE_EVENT -> if (state is LivePauseState.Shifted) Intent.GoLive else Intent.Unhandled
        else -> Intent.Unhandled
    }

    fun forAction(action: PlayerControlsAction, state: LivePauseState): Intent = when (action) {
        PlayerControlsAction.TogglePlayback, PlayerControlsAction.KeyboardTogglePlayback -> Intent.Toggle
        PlayerControlsAction.SeekBack, PlayerControlsAction.KeyboardSeekBack -> step(state, -SEEK_STEP_MS)
        PlayerControlsAction.SeekForward, PlayerControlsAction.KeyboardSeekForward -> step(state, SEEK_STEP_MS)
        else -> Intent.Unhandled
    }

    fun forScrubFinish(positionMs: Long, state: LivePauseState): Intent =
        if (scrubbable(state) != null) Intent.Seek(positionMs) else Intent.Unhandled

    private fun step(state: LivePauseState, deltaMs: Long): Intent {
        val s = scrubbable(state) ?: return Intent.Unhandled
        return Intent.Seek((s.positionMs + deltaMs).coerceIn(s.windowStartMs, s.windowEndMs))
    }

    private fun scrubbable(state: LivePauseState): LivePauseState.Shifted? =
        (state as? LivePauseState.Shifted)?.takeIf { it.windowEndMs - it.windowStartMs >= MIN_WINDOW_MS }

    private fun ceilTo(ms: Long): Long = ((ms + QUANTUM_MS - 1) / QUANTUM_MS) * QUANTUM_MS
}
