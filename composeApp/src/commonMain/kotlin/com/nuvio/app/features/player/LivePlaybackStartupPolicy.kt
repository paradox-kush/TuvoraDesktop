package com.nuvio.app.features.player

/**
 * One source attempt; independent of the player, network and wall clock.
 *
 * Judges a channel's FIRST open only. [recoveringPlayedChannel] marks an automatic reconnect of a
 * channel that already played (the freeze watcher re-resolves through the same path as Retry): its
 * failures belong to the reconnect ladder, which keeps trying until the provider is back.
 */
class LivePlaybackStartupPolicy(private val recoveringPlayedChannel: Boolean = false) {
    private var started = false
    private var failed = false

    /** True is terminal for this attempt. A Retry/channel switch creates a fresh policy. */
    fun sample(snapshot: PlayerPlaybackSnapshot, elapsedMs: Long): Boolean {
        if (recoveringPlayedChannel) return false
        if (failed) return true
        started = started || hasStarted(snapshot)
        failed = !started && (snapshot.isEnded || elapsedMs >= TIMEOUT_MS)
        return failed
    }

    companion object {
        // Allows slow probes/GOPs; a concrete failure ends the attempt immediately.
        const val TIMEOUT_MS = 20_000L

        /**
         * Playback has really begun: the playhead moved or a frame was drawn. Never the bare
         * isPlaying flag — Android libmpv echoes `pause=false` into it before the file has loaded,
         * so a channel that then failed to open read as started, its end read as a freeze, and the
         * docked screen reconnected forever instead of showing the error (1.13.4).
         */
        fun hasStarted(snapshot: PlayerPlaybackSnapshot): Boolean =
            snapshot.positionMs > 0L || snapshot.videoProgressTicks > 0L

        /**
         * A FILM/EPISODE source whose open failed: mpv ended the file with "error" before anything played or even a duration was
         * known (a refused certificate, a 404, an unreachable host). The native snapshot stays "loading" forever in that state,
         * so the screen would spin with no way out - report it as a playback error instead. Live channels have their own
         * handling ([endFileSnapshot]); an error after playback began is a stream drop, not an open failure.
         */
        fun vodOpenFailed(snapshot: PlayerPlaybackSnapshot, reason: String?, isLive: Boolean): Boolean =
            !isLive && reason == "error" && !hasStarted(snapshot) && snapshot.durationMs == 0L

        /** eof-reached may already be cleared by the time END_FILE is dispatched. */
        fun endFileSnapshot(snapshot: PlayerPlaybackSnapshot, reason: String?, isLive: Boolean): PlayerPlaybackSnapshot =
            if (isLive && (reason == "eof" || reason == "error")) {
                snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
            } else snapshot
    }
}
