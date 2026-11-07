package com.nuvio.app.core.telemetry.api

/** Everything OS-specific the telemetry core needs, supplied by each host. */
interface TelemetryHost {
    fun nowMs(): Long

    /** 0 until [bound) from a cryptographically secure source. */
    fun secureRandomInt(bound: Int): Int

    /** App-private text files under the telemetry folder. All calls may block; none may be on Main. */
    fun readFile(name: String): String?

    /** Atomic: temp file + rename. */
    fun writeFileAtomic(name: String, text: String): Boolean

    fun deleteFile(name: String)

    /** Whether the user has the "Crash & usage reports" switch on. */
    fun consentGranted(): Boolean

    /** Code-only memory numbers (used_mb, max_mb, ...). */
    fun memorySample(): Map<String, Any>

    /** Coarse hardware facts: no serials, MACs, IPs or app lists. */
    fun hardwareProfile(): Map<String, Any>

    fun appVersion(): String

    /** Sends one event through the SDK, stamping [atMs] as the event time when the SDK allows it. */
    fun capture(event: String, props: Map<String, Any>, atMs: Long?)

    fun flush()
}
