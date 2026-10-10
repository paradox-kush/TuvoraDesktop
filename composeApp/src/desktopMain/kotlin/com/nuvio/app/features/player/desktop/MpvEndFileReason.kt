package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.LivePlaybackStartupPolicy
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

/**
 * The last mpv END_FILE a desktop bridge saw for the current file, as reported by
 * [NativePlayerBridge.endFileReason] (the raw `mpv_end_file_reason` code, [NONE] when none since
 * the last START_FILE).
 *
 * The snapshot is polled, and after a failed open mpv sits idle with `eof-reached` unavailable, so
 * without this a dead live channel never reads as ended. The live-only decision itself is the
 * shared [LivePlaybackStartupPolicy.endFileSnapshot], the same one Android applies to its event.
 */
internal object MpvEndFileReason {
    const val NONE = -1

    /** mpv/client.h `mpv_end_file_reason` → the names mpv's own event node uses. */
    fun name(code: Int): String? = when (code) {
        0 -> "eof"
        2 -> "stop"
        3 -> "quit"
        4 -> "error"
        5 -> "redirect"
        else -> null
    }

    fun applyTo(snapshot: PlayerPlaybackSnapshot, code: Int, isLive: Boolean): PlayerPlaybackSnapshot =
        LivePlaybackStartupPolicy.endFileSnapshot(snapshot, name(code), isLive)
}
