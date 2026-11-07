package com.nuvio.app.core.telemetry.api

/** One code-only record in the on-device flight recorder. No free text, no URLs. */
data class DiagEntry(
    val atMs: Long,
    val area: DiagArea,
    val code: String,
    val fields: Map<String, Any> = emptyMap(),
)

enum class DiagArea { PLAYBACK, IPTV_LOAD, EPG, SCREEN, MEMORY, LIFECYCLE }

enum class ReportCategory { WONT_PLAY, BLACK_SCREEN, FREEZES, APP_CLOSED, OTHER }

enum class ReportSurface { LIVE, VOD, SETTINGS }

/** What the caller already knows when it asks for a report (all optional, code-only). */
data class ReportContext(
    val engine: String? = null,
    val failureCode: String? = null,
)

sealed interface ReportResult {
    data class Sent(val supportCode: String) : ReportResult
    data object ConsentOff : ReportResult
    data class RateLimited(val retryInSec: Int) : ReportResult
    data object Failed : ReportResult
}

/**
 * The one door feature code uses for diagnostics. Implemented in `core/telemetry/internal`
 * and installed once at the composition root of each host via [TelemetryAccess].
 */
interface TelemetryPort {
    val supportCode: String

    /** Local only: adds a record to the rolling log. Never sends. */
    fun record(entry: DiagEntry)

    /** A failure worth sending now: the event rides with the recent trail. */
    fun reportError(event: String, props: Map<String, Any>)

    suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        context: ReportContext = ReportContext(),
    ): ReportResult
}

/** Display form of the support code, `XXXX-XXXX`. */
fun formatSupportCode(code: String): String =
    if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
