package com.nuvio.app.core.telemetry.api

import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * QA mirror (contract C): when the QA switch is on, every playback signal and memory sample is also written as ONE
 * line `TuvoraQa {"ev":"ttff","kind":"live","engine":"libmpv","ms":1840}` with the exact values (the count keys carry
 * only buckets). Local only: the sink is a log line, never the network, and a value that could carry something the
 * privacy rules forbid is dropped here too. Off by default; with no sink configured [mirror] returns before building
 * anything.
 *
 * The switch is read ONCE at startup by the platform (Desktop: JVM property `tuvora.qa` or env `TUVORA_QA` equal to
 * `1`; Android: system property `debug.tuvora.qa`) and handed to [configure] with that platform's sink.
 */
object QaMirror {
    const val TAG = "TuvoraQa"
    const val PROPERTY = "tuvora.qa"
    const val ENV = "TUVORA_QA"

    private val sink = atomic<((String) -> Unit)?>(null)
    private val safeValue = Regex("""^[a-z0-9_:.\-]{1,48}$""")

    /** True when either source says exactly `1`. */
    fun enabledBy(property: String?, env: String? = null): Boolean = property?.trim() == "1" || env?.trim() == "1"

    /** [enabled] false clears the sink (nothing is ever written). [write] receives the whole line, tag included. */
    fun configure(enabled: Boolean, write: (String) -> Unit) {
        sink.value = if (enabled) write else null
    }

    val isOn: Boolean get() = sink.value != null

    /** [fields] is evaluated only when the mirror is on. */
    fun mirror(ev: String, fields: () -> Map<String, Any>) {
        val write = sink.value ?: return
        val line = runCatching { format(ev, fields()) }.getOrNull() ?: return
        runCatching { write(line) }
    }

    /** `TuvoraQa {json}`, or null when [ev] is not a clean token. Unsafe fields are dropped, never redacted-and-kept. */
    fun format(ev: String, fields: Map<String, Any>): String? {
        if (!safeValue.matches(ev)) return null
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        out["ev"] = JsonPrimitive(ev)
        for ((k, v) in fields) {
            if (k == "ev" || PlaybackTelemetryPrivacy.isForbiddenKey(k)) continue
            when (v) {
                is Boolean -> out[k] = JsonPrimitive(v)
                is Int -> out[k] = JsonPrimitive(v)
                is Long -> out[k] = JsonPrimitive(v)
                is String -> if (safeValue.matches(v)) out[k] = JsonPrimitive(v)
                else -> Unit
            }
        }
        return "$TAG ${JsonObject(out)}"
    }

    /** Tests: back to off. */
    fun resetForTest() { sink.value = null }
}
