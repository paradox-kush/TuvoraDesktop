package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The streaming `get_simple_data_table` parser: the only thing that ever holds a panel's history.
 *
 * Nothing here materializes the body. The XMLTV OOM this codebase already ate came from exactly
 * the opposite shape, and a panel that keeps 14 days of guide for a 24-hour channel ships a lot
 * more than the day the guide can show.
 */
class XtreamEpgTableParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private class Collected {
        val programmes = ArrayList<XtreamProgram>()
    }

    /** Feeds [body] in [chunkSize] slices so every test also exercises chunk-boundary handling. */
    private fun parse(
        body: String,
        catchUpDays: Int = 7,
        chunkSize: Int = Int.MAX_VALUE,
    ): Collected {
        val out = Collected()
        val parser = XtreamEpgTableParser(json, nowMs = now, catchUpDays = catchUpDays) {
            out.programmes.add(it)
        }
        var i = 0
        while (i < body.length) {
            val end = minOf(i + chunkSize, body.length)
            parser.feed(body.substring(i, end))
            i = end
        }
        parser.finish()
        return out
    }

    private fun b64(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = s.encodeToByteArray()
        return buildString {
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                append(alphabet[b0 ushr 2])
                append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
                append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
                i += 3
            }
        }
    }

    private fun row(
        title: String,
        startMs: Long,
        endMs: Long,
        hasArchive: String? = null,
        encode: Boolean = true,
    ): String {
        val t = if (encode) b64(title) else title
        val archive = hasArchive?.let { ""","has_archive":$it""" } ?: ""
        return """{"id":"1","title":"$t","description":"","start_timestamp":"${startMs / 1000}",""" +
            """"stop_timestamp":"${endMs / 1000}","now_playing":0$archive}"""
    }

    private fun body(vararg rows: String) = """{"epg_listings":[${rows.joinToString(",")}]}"""

    // --- the happy path -------------------------------------------------------------------

    @Test
    fun `rows inside the window are emitted in order`() {
        val out = parse(
            body(
                row("The One Show", now - 2 * hour, now - 1 * hour),
                row("Gardeners World", now - 1 * hour, now),
                row("Match of the Day", now, now + hour),
            )
        )
        assertEquals(3, out.programmes.size)
        assertEquals("The One Show", out.programmes[0].title)
        assertEquals("Gardeners World", out.programmes[1].title)
        assertEquals("Match of the Day", out.programmes[2].title)
    }

    /** Seconds-as-strings become milliseconds — ms/s confusion puts programmes in year 52,000. */
    @Test
    fun `timestamps convert from seconds to milliseconds`() {
        val out = parse(body(row("News", now - hour, now)))
        assertEquals(now - hour, out.programmes.single().startMs)
        assertEquals(now, out.programmes.single().endMs)
    }

    /**
     * `has_archive` is the panel marking, recording by recording, what it kept — the strongest
     * catch-up signal there is. Panels send it as an int OR a quoted string; absence is silence,
     * which must stay null rather than collapsing to "no".
     */
    @Test
    fun `per-programme has_archive parses from int string and absent`() {
        val out = parse(
            body(
                row("marked int", now - 3 * hour, now - 2 * hour, hasArchive = "1"),
                row("marked string", now - 2 * hour, now - hour, hasArchive = "\"1\""),
                row("unmarked", now - hour, now, hasArchive = "0"),
                row("silent", now, now + hour),
            )
        )
        assertEquals(true, out.programmes[0].hasArchive)
        assertEquals(true, out.programmes[1].hasArchive)
        assertEquals(false, out.programmes[2].hasArchive)
        assertEquals(null, out.programmes[3].hasArchive, "absence is silence, not a denial")
    }

    /** The body arrives in whatever slices the transport hands over — mid-token, mid-string. */
    @Test
    fun `the parse survives chunk boundaries anywhere`() {
        val text = body(
            row("The One Show", now - 2 * hour, now - hour),
            row("Gardeners World", now - hour, now),
        )
        listOf(1, 3, 7, 64).forEach { size ->
            val out = parse(text, chunkSize = size)
            assertEquals(2, out.programmes.size, "chunk size $size lost rows")
            assertEquals("Gardeners World", out.programmes[1].title, "chunk size $size corrupted a row")
        }
    }

    // --- base64 ---------------------------------------------------------------------------

    /** The normal case: panels base64 the title and description. */
    @Test
    fun `base64 titles decode once at parse`() {
        val out = parse(body(row("Antiques Roadshow", now - hour, now)))
        assertEquals("Antiques Roadshow", out.programmes.single().title)
    }

    /**
     * THE FALLBACK. Some panels don't encode at all, and a short plain title can be *valid base64
     * by accident* — "News" is four characters from the alphabet, so a naive decode succeeds and
     * returns mojibake. The guard has to notice the decode produced nonsense and keep the original.
     */
    @Test
    fun `an unencoded title survives as plain text`() {
        val out = parse(
            body(
                row("News", now - 3 * hour, now - 2 * hour, encode = false),
                row("Sport", now - 2 * hour, now - hour, encode = false),
                row("Film: The Long Good Friday", now - hour, now, encode = false),
            )
        )
        assertEquals("News", out.programmes[0].title)
        assertEquals("Sport", out.programmes[1].title)
        assertEquals("Film: The Long Good Friday", out.programmes[2].title)
    }

    // --- the window cap -------------------------------------------------------------------

    /** Rows older than the parse window never reach the heap, let alone the database. */
    @Test
    fun `rows older than the window are skipped at parse`() {
        val out = parse(
            body(
                row("ancient", now - 30 * day, now - 30 * day + hour),
                row("kept", now - hour, now),
            )
        )
        assertEquals(1, out.programmes.size)
        assertEquals("kept", out.programmes.single().title)
    }

    /** And rows past the forward horizon — a fortnight of schedule is the OOM risk, not the past. */
    @Test
    fun `rows past the forward horizon are skipped at parse`() {
        val out = parse(
            body(
                row("kept", now, now + hour),
                row("far future", now + 40 * hour, now + 41 * hour),
            )
        )
        assertEquals(1, out.programmes.size)
        assertEquals("kept", out.programmes.single().title)
    }

    /** A provider with a wider window keeps more of its own history. */
    @Test
    fun `a wider provider window keeps older rows`() {
        val text = body(row("ten days ago", now - 10 * day, now - 10 * day + hour))
        assertEquals(0, parse(text, catchUpDays = 7).programmes.size)
        assertEquals(1, parse(text, catchUpDays = 14).programmes.size)
    }

    // --- malformed input ------------------------------------------------------------------

    /** One bad element is skipped; the rest of the channel's guide still lands. */
    @Test
    fun `a malformed element is skipped without losing the rest`() {
        val out = parse(
            """{"epg_listings":[""" +
                row("before", now - 2 * hour, now - hour) + "," +
                """{"title":"broken","start_timestamp":}""" + "," +
                row("after", now - hour, now) +
                "]}"
        )
        assertEquals(2, out.programmes.size)
        assertEquals("before", out.programmes[0].title)
        assertEquals("after", out.programmes[1].title)
    }

    /** Degenerate rows are refused at parse, so no window read can ever surface one. */
    @Test
    fun `degenerate rows are skipped`() {
        val out = parse(
            body(
                row("zero start", 0L, now),
                row("inverted", now - hour, now - 2 * hour),
                row("zero length", now - hour, now - hour),
                row("good", now - hour, now),
            )
        )
        assertEquals(1, out.programmes.size)
        assertEquals("good", out.programmes.single().title)
    }

    /** A panel answering an error object where the table should be must not read as "no guide". */
    @Test
    fun `a body with no listings array throws rather than reading as empty`() {
        assertFailsWith<IllegalStateException> {
            val parser = XtreamEpgTableParser(json, nowMs = now, catchUpDays = 7) {}
            parser.feed("""{"user_info":{"auth":0}}""")
            parser.finish()
        }
    }

    /**
     * A body cut off mid-array throws too: the caller DELETEs the channel's rows in the same
     * transaction as the insert, so committing a half-received day would silently lose the rest.
     */
    @Test
    fun `a truncated body throws rather than committing a partial channel`() {
        assertFailsWith<IllegalStateException> {
            val parser = XtreamEpgTableParser(json, nowMs = now, catchUpDays = 7) {}
            parser.feed("""{"epg_listings":[""" + row("half", now - hour, now))
            parser.finish()
        }
    }

    /** An empty but well-formed table is a real answer: this channel has no guide. */
    @Test
    fun `an empty listings array is a valid empty answer`() {
        val out = parse("""{"epg_listings":[]}""")
        assertTrue(out.programmes.isEmpty())
    }

    /**
     * The key scan must only match `epg_listings` as a KEY. A description whose text contains the
     * word must not open a second array and corrupt the parse.
     */
    @Test
    fun `the key scan is not fooled by the word appearing in a value`() {
        val out = parse(
            """{"note":"epg_listings are below","epg_listings":[""" +
                row("real", now - hour, now) + "]}"
        )
        assertEquals(1, out.programmes.size)
        assertEquals("real", out.programmes.single().title)
    }
}
