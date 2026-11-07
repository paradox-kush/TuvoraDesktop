package com.nuvio.app.core.telemetry.api

import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay

/**
 * Hands the installed [TelemetryPort] to UI and feature code. A host installs it once at startup;
 * until then (or in tests) calls are harmless no-ops and the support code is empty.
 */
object TelemetryAccess {
    @Volatile
    private var installed: TelemetryPort? = null

    fun install(port: TelemetryPort) {
        installed = port
    }

    val port: TelemetryPort get() = installed ?: NoopTelemetryPort

    val isInstalled: Boolean get() = installed != null

    @Volatile
    private var memorySampler: (() -> Map<String, Any>)? = null

    fun install(port: TelemetryPort, memorySampler: (() -> Map<String, Any>)?) {
        installed = port
        this.memorySampler = memorySampler
    }

    /**
     * Records one memory sample every [intervalMs] until cancelled. Call it only from inside an
     * active playback scope (a LaunchedEffect on the player) so it stops with playback.
     */
    suspend fun sampleMemoryWhileActive(intervalMs: Long = 60_000L) {
        while (true) {
            val sample = memorySampler?.invoke()
            if (sample != null) port.record(DiagEntry(0L, DiagArea.MEMORY, "sample", sample))
            delay(intervalMs)
        }
    }

    private object NoopTelemetryPort : TelemetryPort {
        override val supportCode: String = ""
        override fun record(entry: DiagEntry) = Unit
        override fun reportError(event: String, props: Map<String, Any>) = Unit
        override suspend fun sendUserReport(
            category: ReportCategory,
            surface: ReportSurface,
            context: ReportContext,
        ): ReportResult = ReportResult.Failed
    }
}
