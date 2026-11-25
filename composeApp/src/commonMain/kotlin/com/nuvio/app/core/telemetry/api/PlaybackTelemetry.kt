package com.nuvio.app.core.telemetry.api

/**
 * Feature-facing door for the playback telemetry signals that are COUNTED (contract A). A routine success is never an
 * event: each call adds one to a `session_health` count key and, when the QA mirror is on, writes one exact-value line.
 * Everything goes through the [TelemetryPort], so the user's "Crash & usage reports" switch gates it like every other
 * signal; there is no second switch.
 *
 * [port] is a parameter so call sites stay one-liners and tests pass a recording port.
 */
object PlaybackTelemetry {
    /** Milliseconds from the play request to the first rendered frame (or the engine's existing first-frame signal). */
    fun ttff(kind: String, engine: String, ms: Long, port: TelemetryPort = TelemetryAccess.current()) {
        val k = PlaybackTelemetryKeys.kind(kind) ?: return
        val e = PlaybackTelemetryKeys.engine(engine)
        PlaybackTelemetryKeys.ttff(k, e, ms)?.let { runCatching { port.count(it) } }
        QaMirror.mirror("ttff") { linkedMapOf("kind" to k, "engine" to e, "ms" to ms.coerceAtLeast(0L)) }
    }

    /** A stall that ended: [recovered] false means it was given up on (by the ladder or the viewer). */
    fun stall(kind: String, recovered: Boolean, ms: Long, port: TelemetryPort = TelemetryAccess.current()) {
        val k = PlaybackTelemetryKeys.kind(kind) ?: return
        PlaybackTelemetryKeys.stall(k, recovered)?.let { runCatching { port.count(it) } }
        PlaybackTelemetryKeys.stallMs(k, ms)?.let { runCatching { port.count(it) } }
        QaMirror.mirror("stall") {
            linkedMapOf("kind" to k, "outcome" to (if (recovered) "recovered" else "gave_up"), "ms" to ms.coerceAtLeast(0L))
        }
    }

    /** The engine's native teardown took [ms] and [failed] or not. */
    fun teardown(engine: String, ms: Long, failed: Boolean, port: TelemetryPort = TelemetryAccess.current()) {
        val e = PlaybackTelemetryKeys.engine(engine)
        runCatching { port.count(PlaybackTelemetryKeys.teardown(e, ms, failed)) }
        QaMirror.mirror("teardown") {
            linkedMapOf("engine" to e, "outcome" to PlaybackTelemetryKeys.teardownOutcome(ms, failed), "ms" to ms.coerceAtLeast(0L))
        }
    }
}
