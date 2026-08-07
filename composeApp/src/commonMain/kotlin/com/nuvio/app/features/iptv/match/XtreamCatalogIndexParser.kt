package com.nuvio.app.features.iptv.match

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reads a `get_vod_streams` / `get_series` / `get_live_streams` array one element at a time,
 * fed by whatever chunks the transport hands over.
 *
 * The bulk lists used to go through [com.nuvio.app.features.addons.httpGetText], which reads the
 * WHOLE response into one byte array and then into one String before
 * `Json.parseToJsonElement` builds a JsonElement tree over the entire catalog. For a large
 * provider that first step alone is a ~27 MB contiguous allocation, and a phone sitting at
 * ~80 MB of Java heap simply can't serve it:
 *
 *     OutOfMemoryError: Failed to allocate a 26891064 byte allocation
 *         at okio.Buffer.readByteArray → ResponseBody.bytes → readResponseBody
 *
 * The throw was caught, so nothing crashed — TMDB enrichment for that provider just silently
 * never happened, and the allocation churn stalled the UI behind blocking-GC pauses. Here
 * nothing is retained but the current element (a few hundred bytes) and the growing result
 * list, so peak memory no longer scales with catalog size. KMP twin of NuvioTV's
 * XtreamCatalogIndexParser, which gets the same result from Moshi's JsonReader over the okio
 * source; Moshi isn't available in commonMain, so the array is split here and each element is
 * handed to the existing per-item parsers unchanged.
 *
 * Splitting rather than tokenizing is deliberate: every field-level quirk (int vs quoted
 * string vs bare bool) stays the job of the `parse*Item` functions that already handle it, so
 * this change moves no parsing semantics at all. One malformed element is skipped instead of
 * sinking the whole catalog, which is strictly better than the old whole-document decode.
 *
 * Not thread-safe — one instance per response, driven from the transport's reader thread.
 */
internal class XtreamCatalogIndexParser<T>(
    private val json: Json,
    private val map: (JsonObject) -> T?,
    /**
     * When set, each parsed element is handed here instead of accumulated — [finish] then returns an
     * empty list and [finishCount] carries the tally. This is what keeps a 175k-item catalog from
     * ever existing in heap as one list: the index build streams elements straight into the
     * SQLite session ([XtreamMatchIndex.beginSync]) the way the M3U ingest streams lines.
     */
    private val sink: ((T) -> Unit)? = null,
) {
    private val out = ArrayList<T>()
    private val element = StringBuilder()

    /** Elements delivered (to [sink] or [out]) — the streamed replacement for `finish().size`. */
    var deliveredCount: Int = 0
        private set

    private var started = false     // consumed the array's opening '['
    private var finished = false    // consumed its closing ']'
    private var depth = 0           // nesting inside the element being collected
    private var inString = false
    private var escaped = false

    /**
     * Consumes the next slice of the response. Chunks may split anywhere — mid-token,
     * mid-string, even mid-escape-sequence — because the scanner's state lives on the
     * instance rather than in the chunk.
     */
    fun accept(chunk: String) {
        for (c in chunk) {
            if (finished) return

            if (inString) {
                element.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }

            if (!started) {
                if (c.isWhitespace()) continue
                // A panel that errors mid-session answers with an object ({"user_info":…}) where
                // the catalog should be. That must read as a failed fetch so the caller backs off
                // — as an empty catalog it would look like "the provider has no movies", and an
                // empty list is accepted on a first build.
                if (c != '[') error("expected a catalog array, got '$c'")
                started = true
                continue
            }

            when (c) {
                '"' -> { inString = true; element.append(c) }
                '{', '[' -> { depth++; element.append(c) }
                '}' -> {
                    if (depth > 0) depth--
                    element.append(c)
                    if (depth == 0) flush()
                }
                ']' -> {
                    if (depth == 0) {
                        flush()
                        finished = true
                    } else {
                        depth--
                        element.append(c)
                        if (depth == 0) flush()
                    }
                }
                ',' -> if (depth == 0) flush() else element.append(c)
                else -> element.append(c)
            }
        }
    }

    /**
     * Result for a fully-received array.
     *
     * A body that ends before the closing ']' throws rather than returning what arrived: the
     * caller diffs this list against the stored index and DELETES anything missing, so handing
     * back a truncated catalog would quietly wipe titles. The old whole-document decode failed
     * loudly on a truncated body too, and that has to stay true.
     */
    fun finish(): List<T> {
        check(started) { "expected a catalog array, got an empty response" }
        check(finished) { "catalog response ended mid-array" }
        return out
    }

    /**
     * [finish] for sink mode: same truncation guards (a body that ends mid-array still throws —
     * a streamed consumer has already applied its elements, but the caller must NOT finalize
     * (delete vanished rows / bump built_at) on a partial catalog), returning the tally instead
     * of a list.
     */
    fun finishCount(): Int {
        check(started) { "expected a catalog array, got an empty response" }
        check(finished) { "catalog response ended mid-array" }
        return deliveredCount
    }

    /** Parses one collected element and delivers its mapping. Blank/garbage elements are skipped. */
    private fun flush() {
        val text = element.toString().trim()
        element.clear()
        if (text.isEmpty()) return
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val mapped = map(obj) ?: return
        deliveredCount++
        sink?.invoke(mapped) ?: out.add(mapped)
    }
}
