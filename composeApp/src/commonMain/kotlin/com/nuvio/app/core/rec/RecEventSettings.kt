package com.nuvio.app.core.rec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_ENABLED = "logging_enabled"
private const val KEY_SUPPRESSED_UNTIL = "suppressed_until_ms"

/** How long a 410 from the ingest endpoint silences the client. */
const val REC_KILL_SWITCH_BACKOFF_MS = 24 * 60 * 60 * 1000L

/**
 * The two things that can stop the recommendation logger: the user's own opt-out, and the
 * backend's kill switch. Twin of NuvioTV's `RecEventSettings`.
 *
 * There is no remote feature-flag system in this app, so the kill switch IS the remote control:
 * `rec_ingest_config.enabled = false` makes the edge function answer 410 and every client goes
 * quiet for 24h — the only lever available if the stream needs stopping without a release.
 */
object RecEventSettings {
    private val _enabled = MutableStateFlow(RecEventStorage.loadBoolean(KEY_ENABLED, true))

    /** "Share anonymous usage to improve recommendations" — on by default, off instantly. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        // Rotate BEFORE re-enabling so the first event of the new stream already carries the new
        // id; an opt-out cycle should read as a new device, not a gap in an existing one.
        if (value) RecEventIdentity.rotateDeviceId()
        RecEventStorage.saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    fun suppressUntil(nowMs: Long) {
        RecEventStorage.saveLong(KEY_SUPPRESSED_UNTIL, nowMs + REC_KILL_SWITCH_BACKOFF_MS)
    }

    /** True when events may be collected and sent right now. */
    fun isActive(nowMs: Long): Boolean =
        _enabled.value && nowMs >= RecEventStorage.loadLong(KEY_SUPPRESSED_UNTIL, 0L)
}
