package com.nuvio.app.core.telemetry.internal

import java.nio.file.Files
import java.nio.file.Path

/** Counters for events the SDK's on-disk queue still holds, sent with `session_health`. Numbers only. */
internal data class TelemetryQueueBacklog(val backlog: Int, val oldestHours: Int) {
    fun asProps(): Map<String, Any> = mapOf("tele_queue_backlog" to backlog, "tele_queue_oldest_h" to oldestHours)
}

/** Pure: how stale is the SDK's on-disk queue. A growing backlog means delivery is failing. */
internal object TelemetryQueueBacklogPolicy {
    const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

    /** [modifiedAtMs] is the last-modified time of each queued file. A clock-skewed future time counts as fresh. */
    fun evaluate(modifiedAtMs: List<Long>, nowMs: Long): TelemetryQueueBacklog {
        val ages = modifiedAtMs.map { (nowMs - it).coerceAtLeast(0L) }
        return TelemetryQueueBacklog(
            backlog = ages.count { it > STALE_AFTER_MS },
            oldestHours = ((ages.maxOrNull() ?: 0L) / HOUR_MS).toInt(),
        )
    }
}

/** The SDK's queue directory on disk (impure helpers). Both never throw. */
internal object DesktopTelemetryQueue {
    /** The SDK keeps its event files in `<storagePrefix>/<apiKey>/`. */
    fun dir(queueRoot: Path, apiKey: String): Path = queueRoot.resolve(apiKey)

    fun scan(queueRoot: Path, apiKey: String, nowMs: Long): TelemetryQueueBacklog = runCatching {
        val dir = dir(queueRoot, apiKey)
        if (!Files.isDirectory(dir)) return TelemetryQueueBacklog(0, 0)
        val times = Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { Files.getLastModifiedTime(it).toMillis() }.toList()
        }
        TelemetryQueueBacklogPolicy.evaluate(times, nowMs)
    }.getOrDefault(TelemetryQueueBacklog(0, 0))

    /**
     * Deletes queued events. Called when diagnostics are off: the SDK's flush timer does not check opt-out, so files
     * left from while reports were ON would otherwise still be uploaded after the user turned them off. A deque entry
     * whose file is gone is dropped harmlessly by the SDK.
     */
    fun purge(queueRoot: Path, apiKey: String) {
        runCatching {
            val dir = dir(queueRoot, apiKey)
            if (!Files.isDirectory(dir)) return
            Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
