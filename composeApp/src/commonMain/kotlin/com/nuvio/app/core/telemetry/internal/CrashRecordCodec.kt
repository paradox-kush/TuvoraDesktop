package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A crash as written to `pending_crash.json`, v1. Never contains the exception message. */
internal data class CrashRecord(
    val crashId: String,
    val supportCode: String,
    val appVersion: String,
    val atMs: Long,
    val thread: String,
    val exceptionType: String,
    val stack: List<String>,
    val trail: List<DiagEntry>,
    val memory: Map<String, Any>,
    val screen: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object CrashRecordCodec {
    const val MAX_FRAMES = 64
    private const val MAX_LINE = 200
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(r: CrashRecord): String = JsonObject(
        mapOf(
            "schema_version" to JsonPrimitive(r.schemaVersion),
            "crash_id" to JsonPrimitive(r.crashId),
            "support_code" to JsonPrimitive(r.supportCode),
            "app_version" to JsonPrimitive(r.appVersion),
            "at_ms" to JsonPrimitive(r.atMs),
            "thread" to JsonPrimitive(r.thread),
            "exception_type" to JsonPrimitive(r.exceptionType),
            "stack" to JsonArray(r.stack.take(MAX_FRAMES).map(::JsonPrimitive)),
            "trail" to JsonArray(r.trail.mapNotNull(DiagTrailPolicy::sanitize).takeLast(DiagTrailPolicy.MAX_ENTRIES).map(DiagTrailPolicy::toJson)),
            "memory" to JsonObject(r.memory.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
            "screen" to (r.screen?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
        ),
    ).toString()

    /** Null for anything unreadable: a corrupt record is dropped, never allowed to break startup. */
    fun decode(text: String): CrashRecord? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val screen = (o["screen"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        CrashRecord(
            schemaVersion = o["schema_version"]?.jsonPrimitive?.int ?: 1,
            crashId = o.getValue("crash_id").jsonPrimitive.content,
            supportCode = o["support_code"]?.jsonPrimitive?.content.orEmpty(),
            appVersion = o["app_version"]?.jsonPrimitive?.content.orEmpty(),
            atMs = o.getValue("at_ms").jsonPrimitive.long,
            thread = o["thread"]?.jsonPrimitive?.content.orEmpty(),
            exceptionType = o["exception_type"]?.jsonPrimitive?.content.orEmpty(),
            stack = o["stack"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().take(MAX_FRAMES),
            trail = o["trail"]?.jsonArray?.mapNotNull(DiagTrailPolicy::fromJson).orEmpty(),
            memory = (o["memory"] as? JsonObject)?.mapNotNull { (k, v) ->
                val p = v as? JsonPrimitive ?: return@mapNotNull null
                k to (p.content.toLongOrNull() ?: p.content.toDoubleOrNull() ?: p.content)
            }?.toMap().orEmpty(),
            screen = screen,
        )
    }.getOrNull()

    /**
     * Frames and cause-chain type names from a printed stack trace, with every exception MESSAGE
     * removed (messages routinely quote request URLs and credentials). At most [MAX_FRAMES] lines.
     */
    fun stackFrom(throwable: Throwable): List<String> = scrubTrace(throwable.stackTraceToString())

    fun scrubTrace(trace: String): List<String> {
        val out = ArrayList<String>()
        for (raw in trace.lineSequence()) {
            if (out.size >= MAX_FRAMES) break
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            val kept = when {
                trimmed.startsWith("at ") -> trimmed
                trimmed.startsWith("Caused by:") -> "Caused by: " + typeOnly(trimmed.removePrefix("Caused by:").trim())
                trimmed.startsWith("Suppressed:") -> "Suppressed: " + typeOnly(trimmed.removePrefix("Suppressed:").trim())
                trimmed.startsWith("...") -> trimmed
                out.isEmpty() -> typeOnly(trimmed)
                else -> continue
            }
            out += kept.take(MAX_LINE)
        }
        return out
    }

    fun typeOf(throwable: Throwable): String = throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable"

    private fun typeOnly(s: String): String = s.substringBefore(':').trim().substringBefore(' ')
}
