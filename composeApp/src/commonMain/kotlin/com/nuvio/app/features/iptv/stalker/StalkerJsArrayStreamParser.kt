package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.match.XtreamCatalogIndexParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Streams the row array out of a Stalker list envelope — `{"js":{"data":[…]}}` (what
 * `get_all_channels` answers) or a bare `{"js":[…]}` — one element at a time, fed by whatever
 * chunks the transport hands over.
 *
 * Why (B76): `get_all_channels` is the one Stalker call whose body scales with the lineup — 13 MB for
 * 11k channels on a real portal. Read as one String it went through the plain text client, which on
 * iOS (Ktor Darwin) carries a 60 s WHOLE-REQUEST timeout: a big lineup on a slow line never finished
 * downloading, the lineup came back empty, and the client fell back to paging `type=itv` (up to 200
 * requests, truncated at 2,800 channels) — "live channels keep loading and wouldn't complete" while
 * the small paged movie lists worked. Streamed, the call rides the same transport as the bulk EPG
 * (between-bytes timeout only, on every platform), and no 13 MB String or JsonElement tree is ever
 * built.
 *
 * Only the envelope is scanned here; the array itself is split by [XtreamCatalogIndexParser] so
 * per-element parsing is unchanged. Chunks may split anywhere — the scanner's state lives on the
 * instance. Not thread-safe: one instance per response.
 */
internal class StalkerJsArrayStreamParser<T>(json: Json, map: (JsonObject) -> T?) {

    private val rows = XtreamCatalogIndexParser(json, map)

    private var located = false     // reached the row array's '[' — everything after goes to [rows]
    private var depth = 0           // containers opened so far (envelope = 1, js object = 2)
    private var inString = false
    private var escaped = false
    private val currentString = StringBuilder()
    private var lastString: String? = null
    private var key: String? = null // the key the next value belongs to

    fun accept(chunk: String) {
        if (located) {
            rows.accept(chunk)
            return
        }
        for (i in chunk.indices) {
            val c = chunk[i]
            if (inString) {
                when {
                    escaped -> { escaped = false; currentString.append(c) }
                    c == '\\' -> escaped = true
                    c == '"' -> { inString = false; lastString = currentString.toString() }
                    else -> if (currentString.length < MAX_KEY) currentString.append(c)
                }
                continue
            }
            when (c) {
                '"' -> { inString = true; currentString.clear() }
                ':' -> key = lastString
                ',' -> key = null
                '{' -> { depth++; key = null }
                '[' -> {
                    if ((depth == 1 && key == "js") || (depth == 2 && key == "data")) {
                        located = true
                        rows.accept(chunk.substring(i))
                        return
                    }
                    depth++
                    key = null
                }
                '}', ']' -> depth--
            }
        }
    }

    /**
     * The rows, or an empty list when the envelope carried no row array at all (`{"js":null}`, an
     * object without `data` — a portal that does not offer the call). A body that ended INSIDE the
     * array throws: a truncated lineup must never replace a complete stored one.
     */
    fun finish(): List<T> = if (!located) emptyList() else rows.finish()

    private companion object {
        /** Keys of interest are "js" / "data"; long string VALUES need not be kept. */
        const val MAX_KEY = 16
    }
}
