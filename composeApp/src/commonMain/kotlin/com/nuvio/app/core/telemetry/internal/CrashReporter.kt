package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.TelemetryHost

/**
 * Next-launch delivery of crash records, at-least-once with an idempotency key.
 * `pending_crash.json` -> `sending.json` -> capture + flush -> delete. A leftover `sending.json`
 * is re-sent with the same `crash_id`; queries dedupe by it. Never throws.
 */
internal object CrashReporter {
    const val PENDING = "pending_crash.json"
    const val SENDING = "sending.json"
    const val EVENT = "app_crash_report"

    /**
     * The crash side: builds the record and writes `pending_crash.json` synchronously. No coroutines,
     * DI or blocking locks; never throws. [trail] is the lock-free published tail of the recorder.
     */
    fun writePending(
        host: TelemetryHost,
        supportCode: String,
        trail: List<com.nuvio.app.core.telemetry.api.DiagEntry>,
        threadName: String,
        error: Throwable,
        crashId: String,
    ): Boolean = try {
        val record = CrashRecord(
            crashId = crashId,
            supportCode = supportCode,
            appVersion = host.appVersion(),
            atMs = host.nowMs(),
            thread = threadName.take(64),
            exceptionType = CrashRecordCodec.typeOf(error),
            stack = CrashRecordCodec.stackFrom(error),
            trail = trail,
            memory = host.memorySample(),
            screen = trail.lastOrNull { it.area == com.nuvio.app.core.telemetry.api.DiagArea.SCREEN }?.fields?.get("screen")?.toString(),
        )
        host.writeFileAtomic(PENDING, CrashRecordCodec.encode(record))
    } catch (_: Throwable) {
        false
    }

    /** Returns how many records were sent. When consent is off nothing is sent and the file is deleted. */
    fun sendPending(host: TelemetryHost): Int {
        var sent = 0
        runCatching {
            host.readFile(PENDING)?.let { fresh ->
                // Promote first: if we die while sending, the next start resumes from sending.json.
                if (host.writeFileAtomic(SENDING, fresh)) host.deleteFile(PENDING)
            }
            val text = host.readFile(SENDING) ?: return 0
            val record = CrashRecordCodec.decode(text)
            if (record == null || !host.consentGranted()) {
                host.deleteFile(SENDING)
                return 0
            }
            host.capture(EVENT, props(record, host.hardwareProfile()), record.atMs)
            host.flush()
            host.deleteFile(SENDING)
            sent = 1
        }
        return sent
    }

    fun props(r: CrashRecord, hardware: Map<String, Any>): Map<String, Any> = buildMap {
        putAll(hardware)
        r.memory.forEach { (k, v) -> put("mem_$k", v) }
        put("schema_version", r.schemaVersion)
        put("crash_id", r.crashId)
        put("support_code", r.supportCode)
        put("failed_app_version", r.appVersion)
        put("crashed_at_ms", r.atMs)
        put("thread", r.thread)
        put("exception_type", r.exceptionType)
        put("stack", r.stack.joinToString("\n"))
        put("diag_trail", DiagTrailPolicy.trailJson(r.trail))
        r.screen?.let { put("screen", it) }
    }
}
