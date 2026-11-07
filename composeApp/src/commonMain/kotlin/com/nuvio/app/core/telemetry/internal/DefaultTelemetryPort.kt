package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagArea
import com.nuvio.app.core.telemetry.api.DiagEntry
import com.nuvio.app.core.telemetry.api.ReportCategory
import com.nuvio.app.core.telemetry.api.ReportContext
import com.nuvio.app.core.telemetry.api.ReportResult
import com.nuvio.app.core.telemetry.api.ReportSurface
import com.nuvio.app.core.telemetry.api.TelemetryHost
import com.nuvio.app.core.telemetry.api.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultTelemetryPort(
    private val host: TelemetryHost,
    /** Off-Main scope for disk writes. */
    private val ioScope: CoroutineScope,
    private val sendError: (event: String, props: Map<String, Any>) -> Unit = { e, p -> host.capture(e, p, null) },
) : TelemetryPort {
    private val recorder = FlightRecorder()
    private val health = SessionHealth()
    private val rateLimit = ReportRateLimit()
    private val reportMutex = Mutex()

    override val supportCode: String = loadOrCreateSupportCode()

    /** The ring a previous run left behind, read once before this run overwrites it. */
    val previousRunTrail: List<DiagEntry> = readPersistedRing()

    val flightRecorder: FlightRecorder get() = recorder
    val sessionHealth: SessionHealth get() = health

    override fun record(entry: DiagEntry) {
        val stamped = if (entry.atMs == 0L) entry.copy(atMs = host.nowMs()) else entry
        recorder.record(stamped)
        (stamped.fields["used_mb"] as? Number)?.toLong()?.let(health::observeUsedMb)
    }

    override fun reportError(event: String, props: Map<String, Any>) {
        record(DiagEntry(host.nowMs(), DiagArea.LIFECYCLE, "error", mapOf("reason" to (DiagTrailPolicy.cleanCode(event) ?: "event"))))
        persistRingAsync()
        if (!host.consentGranted()) return
        val withTrail = props + mapOf(
            "diag_trail" to DiagTrailPolicy.trailJson(recorder.tail(TRAIL_ON_ERROR), TRAIL_ON_ERROR),
            "schema_version" to SCHEMA_VERSION,
        )
        runCatching { sendError(event, withTrail) }
    }

    /**
     * Routes an analytics event through [TelemetrySendPolicy]. Returns true when the caller should
     * forward it to the SDK now; false when it was kept local or counted.
     */
    fun route(event: String, props: Map<String, Any>): Boolean = when (TelemetrySendPolicy.decide(event, props)) {
        SendDecision.SEND -> true
        SendDecision.RECORD_ONLY -> {
            record(DiagEntry(host.nowMs(), areaFor(event), DiagTrailPolicy.cleanCode(event.removePrefix("\$")) ?: "event", props + screenField(props)))
            false
        }
        SendDecision.COUNT -> {
            health.count(TelemetrySendPolicy.healthKey(event, props))
            false
        }
    }

    override suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        context: ReportContext,
    ): ReportResult {
        if (!host.consentGranted()) return ReportResult.ConsentOff
        return try {
            reportMutex.withLock {
                val now = host.nowMs()
                rateLimit.retryInSec(now)?.let { return@withLock ReportResult.RateLimited(it) }
                val props = buildMap<String, Any> {
                    putAll(host.hardwareProfile())
                    putAll(host.memorySample().mapKeys { "mem_${it.key}" })
                    put("schema_version", SCHEMA_VERSION)
                    put("report_id", newId())
                    put("support_code", supportCode)
                    put("category", category.name.lowercase())
                    put("surface", surface.name.lowercase())
                    context.engine?.let { DiagTrailPolicy.cleanCode(it) }?.let { put("engine", it) }
                    context.failureCode?.let { DiagTrailPolicy.cleanCode(it) }?.let { put("failure_code", it) }
                    put("app_version", host.appVersion())
                    put("diag_trail", DiagTrailPolicy.trailJson(recorder.tail(TRAIL_ON_REPORT), TRAIL_ON_REPORT))
                }
                host.capture(REPORT_EVENT, props, null)
                host.flush()
                rateLimit.markSent(now)
                ReportResult.Sent(supportCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ReportResult.Failed
        }
    }

    /** One `session_health` event for the session; call on exit. No-op when nothing was counted. */
    fun sendSessionHealth() {
        if (!host.consentGranted()) return
        val counts = health.snapshot() ?: return
        runCatching { host.capture(HEALTH_EVENT, counts + mapOf("schema_version" to SCHEMA_VERSION, "support_code" to supportCode), null) }
    }

    /** Writes the ring only when it changed since the last write (delta-shaped). Blocking: call off Main. */
    fun persistRingIfDirty() {
        if (!recorder.consumeDirty()) return
        runCatching {
            host.writeFileAtomic(RING_FILE, DiagTrailPolicy.trailJson(recorder.lastPublished(), DiagTrailPolicy.MAX_ENTRIES, RING_MAX_BYTES))
        }
    }

    private fun persistRingAsync() {
        recorder.markDirty()
        ioScope.launch { persistRingIfDirty() }
    }

    private fun readPersistedRing(): List<DiagEntry> = runCatching {
        val text = host.readFile(RING_FILE) ?: return@runCatching emptyList()
        (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull(DiagTrailPolicy::fromJson)
    }.getOrDefault(emptyList())

    private fun loadOrCreateSupportCode(): String {
        val stored = runCatching { host.readFile(SUPPORT_CODE_FILE)?.trim() }.getOrNull()
        if (stored != null && SupportCodeGenerator.isValid(stored)) return stored
        val fresh = SupportCodeGenerator.generate(host::secureRandomInt)
        runCatching { host.writeFileAtomic(SUPPORT_CODE_FILE, fresh) }
        return fresh
    }

    private fun newId(): String = buildString {
        repeat(4) { append(host.secureRandomInt(Int.MAX_VALUE).toString(16)) }
    }

    private fun screenField(props: Map<String, Any>): Map<String, Any> =
        props["\$screen_name"]?.let { mapOf("screen" to it) } ?: emptyMap()

    private fun areaFor(event: String): DiagArea = when {
        event.startsWith("\$screen") -> DiagArea.SCREEN
        event.contains("iptv") -> DiagArea.IPTV_LOAD
        event.contains("epg") -> DiagArea.EPG
        event.contains("playback") -> DiagArea.PLAYBACK
        else -> DiagArea.LIFECYCLE
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val REPORT_EVENT = "user_issue_report"
        const val HEALTH_EVENT = "session_health"
        const val TRAIL_ON_ERROR = 40
        const val TRAIL_ON_REPORT = 80
        const val RING_FILE = "ring.json"
        const val SUPPORT_CODE_FILE = "support_code.txt"
        const val RING_MAX_BYTES = 48 * 1024
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
