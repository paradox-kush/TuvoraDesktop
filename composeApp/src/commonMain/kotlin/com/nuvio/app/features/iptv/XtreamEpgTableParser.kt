package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Incremental parser for one channel's `get_simple_data_table` response,
 * `{"epg_listings":[prog,…]}`, fed by whatever chunks the transport hands over (boundaries can
 * fall anywhere — mid-token, mid-string, mid-escape).
 *
 * `get_short_epg` returns only now-and-next, so the replay strip's PAST programmes have to come
 * from here — and this is the whole table, which on a 24-hour channel at a provider keeping a
 * fortnight of guide is far more than the guide can show. Nothing is retained but the current
 * element (a few hundred bytes) and the caller's row buffer, and rows outside the window are
 * refused before they can become objects: the XMLTV OOM this codebase already ate came from
 * reading an EPG body whole.
 *
 * The outer key scan is [com.nuvio.app.features.iptv.stalker.StalkerEpgStreamParser]'s, and the
 * element capture is [com.nuvio.app.features.iptv.match.XtreamCatalogIndexParser]'s — the latter
 * can't be reused directly because it requires the body to BE an array and errors on the `{`.
 * Field-level parsing is delegated to [XtreamClient.parseEpgProgramme] over the small captured
 * element, so every lenient quirk (base64, int-or-quoted-string, `has_archive`) behaves exactly as
 * it does on the short-EPG path.
 *
 * Not thread-safe — one instance per response, driven from the transport's reader thread.
 */
internal class XtreamEpgTableParser(
    private val json: Json,
    private val nowMs: Long,
    private val catchUpDays: Int,
    private val onProgramme: (XtreamProgram) -> Unit,
) {
    // Outer scan: hunting `"epg_listings":` then its `[`.
    private var started = false
    private var finished = false
    private var inString = false
    private var escaped = false
    private val lastString = StringBuilder()
    private var pendingListingsColon = false

    // Element capture, once inside the array.
    private var depth = 0
    private val element = StringBuilder()

    /** Rows delivered to [onProgramme]. */
    var deliveredCount: Int = 0
        private set

    /** Rows refused by the window/degenerate guards — the memory saving, worth logging. */
    var skippedCount: Int = 0
        private set

    fun feed(chunk: String) {
        for (c in chunk) {
            if (finished) return
            if (started) arrayChar(c) else outerChar(c)
        }
    }

    /**
     * Ends the parse.
     *
     * A body with no listings array, or one cut off mid-array, THROWS rather than reading as "this
     * channel has no guide". The caller DELETEs the channel's rows in the same transaction as the
     * insert, so committing a partial answer would silently replace a good day with half of one —
     * and a panel that errors mid-session answers with an object where the table should be, which
     * as an empty result would be stamped as "fetched" and not retried for six hours.
     */
    fun finish(): Int {
        check(started) { "expected an epg_listings array, got none" }
        check(finished) { "epg_listings response ended mid-array" }
        return deliveredCount
    }

    // --- outer ------------------------------------------------------------------------------

    private fun outerChar(c: Char) {
        if (inString) {
            when {
                escaped -> { lastString.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> inString = false
                else -> lastString.append(c)
            }
            return
        }
        when {
            c == '"' -> { inString = true; lastString.clear() }
            // Only a KEY can open the array: the word inside some other field's VALUE closed its
            // own string, so `lastString` is that value and the following ':' never arrives.
            c == ':' -> pendingListingsColon = lastString.toString() == LISTINGS_KEY
            c == '[' && pendingListingsColon -> {
                started = true
                pendingListingsColon = false
                depth = 0
            }
            c.isWhitespace() -> Unit          // ':' may be separated from '[' by whitespace only
            else -> if (c != ',') pendingListingsColon = false
        }
    }

    // --- inside the array -------------------------------------------------------------------

    private fun arrayChar(c: Char) {
        if (inString) {
            element.append(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            return
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

    /** Parses one captured element and admits it, or counts it out. Garbage is skipped, never fatal. */
    private fun flush() {
        val text = element.toString().trim()
        element.clear()
        if (text.isEmpty()) return
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: run {
            skippedCount++
            return
        }
        val programme = runCatching { XtreamClient.parseEpgProgramme(obj) }.getOrNull() ?: run {
            skippedCount++
            return
        }
        if (!CatchUpEpgPolicy.keepsRow(programme.startMs, programme.endMs, nowMs, catchUpDays)) {
            skippedCount++
            return
        }
        deliveredCount++
        onProgramme(programme)
    }

    private companion object {
        const val LISTINGS_KEY = "epg_listings"
    }
}
