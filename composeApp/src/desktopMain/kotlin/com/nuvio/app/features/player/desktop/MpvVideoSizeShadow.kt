package com.nuvio.app.features.player.desktop

import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The video's display size for the 500 ms snapshot, which runs on the UI thread.
 *
 * Reading it takes up to four synchronous `mpv_get_property` calls ([desktopMpvVideoSize]), each
 * waiting on mpv's core lock — the lock a live demuxer can hold, which hitches the UI and risks the
 * AWT<->AppKit deadlock (same reason `demuxer-cache-state` lives on the live worker). So the caller
 * only ever reads the cached value; the read itself is handed to a worker via [current]'s
 * `schedule`, at most one in flight, and lands here for the next tick.
 */
internal class MpvVideoSizeShadow(
    /** Blocking read, run only on the worker; null = could not read now, keep the last size. */
    private val read: () -> IntSize?,
) {
    @Volatile
    private var cached: IntSize = IntSize.Zero
    private val refreshInFlight = AtomicBoolean(false)

    /** Lock-free: returns the last size read and asks [schedule] for a refresh if none is pending. */
    fun current(schedule: (Runnable) -> Unit): IntSize {
        if (refreshInFlight.compareAndSet(false, true)) {
            try {
                schedule(Runnable(::refresh))
            } catch (_: Exception) {
                // Worker stopped (release): keep the last size, try again next tick.
                refreshInFlight.set(false)
            }
        }
        return cached
    }

    private fun refresh() {
        try {
            read()?.let { cached = it }
        } catch (_: Exception) {
            // Keep the last size; a failed read must not blank the picture's aspect ratio.
        } finally {
            refreshInFlight.set(false)
        }
    }

    fun reset() {
        cached = IntSize.Zero
    }
}
