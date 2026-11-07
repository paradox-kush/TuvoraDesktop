package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What may enter the flight recorder and leave the device inside a trail: allow-listed field
 * names, numbers / booleans / short code-like strings only, a URL scrub as a second guard, and a
 * hard size bound. Everything else is dropped, never masked.
 */
internal object DiagTrailPolicy {
    const val MAX_ENTRIES = 80
    const val MAX_BYTES = 12 * 1024

    val ALLOWED_FIELDS: Set<String> = setOf(
        "engine", "phase", "outcome", "reason", "state", "status", "kind", "surface", "screen",
        "attempt", "count", "ms", "duration_ms", "bytes", "http", "bucket", "source",
        "used_mb", "max_mb", "native_mb", "pss_mb", "total_mb", "threads", "error_type", "error_class",
    )

    private val codeLike = Regex("""^[A-Za-z0-9_.:\-]{1,48}$""")
    private val urlish = Regex("""(?i)(://|www\.|\.(com|net|org|tv|io|xyz|me|cc|co)\b|@)""")

    fun cleanCode(code: String): String? =
        code.takeIf { codeLike.matches(it) && !urlish.containsMatchIn(it) }

    /** Null when the entry's code itself is not code-like; otherwise the entry with only safe fields. */
    fun sanitize(entry: DiagEntry): DiagEntry? {
        val code = cleanCode(entry.code) ?: return null
        val fields = buildMap<String, Any> {
            for ((key, value) in entry.fields) {
                if (key !in ALLOWED_FIELDS) continue
                when (value) {
                    is Boolean, is Int, is Long, is Short, is Byte -> put(key, value)
                    is Double -> if (value.isFinite()) put(key, value)
                    is Float -> if (value.isFinite()) put(key, value)
                    is Enum<*> -> cleanCode(value.name)?.let { put(key, it) }
                    is String -> cleanCode(value)?.let { put(key, it) }
                }
            }
        }
        return entry.copy(code = code, fields = fields)
    }

    fun toJson(entry: DiagEntry): JsonObject = JsonObject(
        buildMap {
            put("t", JsonPrimitive(entry.atMs))
            put("a", JsonPrimitive(entry.area.name.lowercase()))
            put("c", JsonPrimitive(entry.code))
            for ((k, v) in entry.fields) put(k, primitive(v))
        },
    )

    private fun primitive(v: Any): JsonElement = when (v) {
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    /** The last [maxEntries] entries as one JSON array string, oldest dropped until it fits [maxBytes]. */
    fun trailJson(entries: List<DiagEntry>, maxEntries: Int = MAX_ENTRIES, maxBytes: Int = MAX_BYTES): String {
        var tail = entries.mapNotNull(::sanitize).takeLast(maxEntries.coerceAtMost(MAX_ENTRIES))
        var json = JsonArray(tail.map(::toJson)).toString()
        while (json.length > maxBytes && tail.isNotEmpty()) {
            tail = tail.drop(maxOf(1, tail.size / 10))
            json = JsonArray(tail.map(::toJson)).toString()
        }
        return json
    }

    fun fromJson(element: JsonElement): DiagEntry? {
        val obj = element as? JsonObject ?: return null
        val at = (obj["t"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val areaName = (obj["a"] as? JsonPrimitive)?.content ?: return null
        val area = com.nuvio.app.core.telemetry.api.DiagArea.entries.firstOrNull { it.name.equals(areaName, true) } ?: return null
        val code = (obj["c"] as? JsonPrimitive)?.content ?: return null
        val fields = buildMap<String, Any> {
            for ((k, v) in obj) {
                if (k == "t" || k == "a" || k == "c" || v is JsonNull) continue
                val p = v as? JsonPrimitive ?: continue
                put(k, p.content.toLongOrNull() ?: p.content.toDoubleOrNull() ?: p.content.toBooleanStrictOrNull() ?: p.content)
            }
        }
        return sanitize(DiagEntry(at, area, code, fields))
    }
}
