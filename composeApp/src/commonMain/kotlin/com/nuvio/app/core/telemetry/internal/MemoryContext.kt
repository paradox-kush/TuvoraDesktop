package com.nuvio.app.core.telemetry.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The last on-device memory sample, kept in one tiny file so the NEXT launch can attach it to `app_exit` (the exit it
 * reports belongs to the run that wrote the sample). Only the host's own numeric facts are kept: whatever a platform
 * cannot measure is simply absent, nothing is estimated.
 */
internal object MemoryContext {
    const val FILE = "last_memory.json"

    /** Names a host's `memorySample()` may use. Desktop: JVM `Runtime` and process facts only. */
    val FIELDS: Set<String> = setOf("java_mb", "java_max_mb", "native_mb", "pss_mb", "ram_free_mb", "threads", "fds")

    fun encode(sample: Map<String, Long>): String =
        JsonObject(sample.filterKeys { it in FIELDS }.mapValues { JsonPrimitive(it.value) }).toString()

    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank() || text.length > 1024) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            for ((k, v) in obj) {
                if (k !in FIELDS) continue
                (v as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }?.let { put(k, it) }
            }
        }
    }

    /** `mem_<name>` properties for an event. */
    fun properties(sample: Map<String, Long>): Map<String, Long> =
        sample.filterKeys { it in FIELDS }.mapKeys { "mem_${it.key}" }

    /** `app_exit` gets the previous run's last sample; an event that already carries `mem_*` keeps its own. */
    fun attach(event: String, props: Map<String, Any>, previous: Map<String, Long>): Map<String, Any> {
        if (event != APP_EXIT || previous.isEmpty()) return props
        val extra = properties(previous).filterKeys { it !in props }
        return if (extra.isEmpty()) props else props + extra
    }

    const val APP_EXIT = "app_exit"
}
