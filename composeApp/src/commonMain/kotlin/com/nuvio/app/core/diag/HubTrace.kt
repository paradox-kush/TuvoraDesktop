package com.nuvio.app.core.diag

import co.touchlab.kermit.Logger

/**
 * Temporary field-diagnosis trace for the "posters don't load after a Stalker scroll + playlist
 * switch" report (S24, 2026-08-16). NOT a permanent logging layer: it exists to answer one
 * question — where the seconds go between a switch and a filled screen — and should be deleted
 * once that is answered.
 *
 * Everything is on ONE tag so a single `adb logcat -s TUVORA_TRACE:V` shows the whole causal
 * chain, with a monotonic-ish millisecond stamp so gaps are readable at a glance.
 *
 * OFF unless [enabled] is set true by the debug entry point — a release build must never pay for
 * the string building, and the volume here would be genuinely harmful in production.
 */
object HubTrace {

    // Plain, not @Volatile: kotlin.jvm.Volatile does not exist in commonMain for Kotlin/Native,
    // and this needs no atomicity — same reasoning as StalkerPlaybackTraffic's flag. The only race
    // is a log line read microseconds after the flag flips, whose worst outcome is a missing line.
    var enabled: Boolean = false

    private val log = Logger.withTag("TUVORA_TRACE")

    /** Set by the Android debug entry point so stamps are readable deltas rather than epoch ms. */
    private var originMs: Long = 0L

    fun start(nowMs: Long) {
        originMs = nowMs
        enabled = true
        log.i { "==== trace started ====" }
    }

    /**
     * [area] is the subsystem (hub / stalker / xtream / poster / image / index), [event] the thing
     * that happened, [detail] free-form. Cheap when disabled: the lambda is never invoked.
     */
    inline fun log(area: String, event: String, detail: () -> String = { "" }) {
        if (!enabled) return
        emit(area, event, detail())
    }

    @PublishedApi
    internal fun emit(area: String, event: String, detail: String) {
        val t = if (originMs == 0L) 0L else nowMs() - originMs
        log.i { "t=${t}ms [$area] $event${if (detail.isEmpty()) "" else " | $detail"}" }
    }

    @PublishedApi
    internal fun nowMs(): Long = com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs()
}
