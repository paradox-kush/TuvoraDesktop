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
        nowMs: Long = now,
        manualOffsetMs: Long? = null,
        clockPairOffsetMs: Long? = null,
    ): Collected {
        val out = Collected()
        val parser = XtreamEpgTableParser(
            json,
            nowMs = nowMs,
            catchUpDays = catchUpDays,
            manualOffsetMs = manualOffsetMs,
            clockPairOffsetMs = clockPairOffsetMs,
        ) {
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

    // --- epoch-skew correction at the parse boundary (the wa12 lie) ---------------------------
    //
    // wa12 (measured live 2026-08-15): the panel builds its epochs from its own wall clock, so
    // every epoch equals its own start STRING read as UTC and is shifted by the panel's zone
    // (+2h). This lane must repair those rows the same way the short-EPG lane does, or the guide
    // timeline and the replay strip would disagree about when the same programme aired.

    /** 2026-08-15 00:00:00 UTC, the probe day; the guide's "now" is 21:40 UTC that evening. */
    private val probeDay = 1_786_752_000L
    private val wa12Now = (probeDay + 21 * 3600 + 40 * 60) * 1000L
    private val panelOffsetMs = 7_200_000L   // the measured clock pair: +2h

    private fun pad(v: Int) = v.toString().padStart(2, '0')

    /** A wa12-shaped row: the start string and the epoch describe the SAME wall-clock digits. */
    private fun liarRow(d: Int, h: Int, m: Int, durMin: Int = 60): String {
        val startSec = probeDay + (d - 15) * 86_400L + h * 3600L + m * 60L
        return """{"title":"${b64("Programme")}","start":"2026-08-${pad(d)} ${pad(h)}:${pad(m)}:00",""" +
            """"start_timestamp":"$startSec","stop_timestamp":"${startSec + durMin * 60L}","has_archive":1}"""
    }

    /** An onnipsite-shaped row: string is panel-local (+1h) but the epoch is true UTC. */
    private fun honestRow(startSec: Long, durMin: Int = 60): String {
        val localSec = startSec + 3600L
        val h = ((localSec % 86_400L) / 3600L).toInt()
        val m = ((localSec % 3600L) / 60L).toInt()
        return """{"title":"${b64("Programme")}","start":"2026-08-15 ${pad(h)}:${pad(m)}:00",""" +
            """"start_timestamp":"$startSec","stop_timestamp":"${startSec + durMin * 60L}"}"""
    }

    @Test
    fun `wa12-shaped rows are auto-corrected so one brackets now`() {
        val out = parse(
            body(liarRow(15, 22, 20), liarRow(15, 23, 20), liarRow(16, 0, 20)),
            nowMs = wa12Now,
            clockPairOffsetMs = panelOffsetMs,
        )
        assertEquals(3, out.programmes.size)
        assertEquals(
            (probeDay + 20 * 3600 + 20 * 60) * 1000L,
            out.programmes.first().startMs,
            "first row corrected to 20:20 UTC",
        )
        assertTrue(
            out.programmes.any { wa12Now in it.startMs until it.endMs },
            "a corrected row brackets now (uncorrected, none did — the field symptom)",
        )
    }

    /** The onnipsite proof: the same clock pair must NOT be subtracted from an honest panel. */
    @Test
    fun `honest rows pass through byte-identical even with a measured clock pair`() {
        val startSec = probeDay + 20 * 3600 + 40 * 60   // true 20:40 UTC
        val out = parse(
            body(honestRow(startSec), honestRow(startSec + 3600)),
            nowMs = wa12Now,
            clockPairOffsetMs = 3_600_000L,
        )
        assertEquals(2, out.programmes.size)
        assertEquals(startSec * 1000L, out.programmes.first().startMs, "epochs untouched")
    }

    /** The manual per-playlist offset wins over the vote — it exists for the residue auto misses. */
    @Test
    fun `a manual offset overrides the auto correction`() {
        val out = parse(
            body(liarRow(15, 22, 20), liarRow(15, 23, 20)),
            nowMs = wa12Now,
            manualOffsetMs = 1_800_000L,
            clockPairOffsetMs = panelOffsetMs,
        )
        assertEquals(
            (probeDay + 22 * 3600 + 20 * 60) * 1000L + 1_800_000L,
            out.programmes.first().startMs,
            "shifted by the manual +30m, not the auto -2h",
        )
    }

    /** Junk rows can't vote, but the parseable majority still repairs the response. */
    @Test
    fun `junk rows do not break the vote in this lane`() {
        val out = parse(
            body(
                """{"title":"x","start_timestamp":"notanumber","stop_timestamp":"alsonot"}""",
                liarRow(15, 22, 20),
                """{"title":"x","start":"garbage","start_timestamp":"0","stop_timestamp":"0"}""",
                liarRow(15, 23, 20),
            ),
            nowMs = wa12Now,
            clockPairOffsetMs = panelOffsetMs,
        )
        assertEquals(2, out.programmes.size, "the two real rows survive, corrected")
        assertEquals((probeDay + 20 * 3600 + 20 * 60) * 1000L, out.programmes.first().startMs)
    }

    /**
     * The keep-window must judge the CORRECTED epochs: a liar's raw epoch can sit past the forward
     * window while the real airing is inside it. Uncorrected, this row would be refused at parse
     * and the guide's forward edge would go blank.
     */
    @Test
    fun `the parse window is applied to corrected epochs`() {
        // Raw epoch at now+37h (outside the 36h forward window); corrected -2h = +35h, inside.
        val out = parse(
            body(liarRow(15, 22, 20), liarRow(15, 23, 20), liarRow(17, 10, 40)),
            nowMs = wa12Now,
            clockPairOffsetMs = panelOffsetMs,
        )
        assertEquals(3, out.programmes.size, "the far row is kept because its corrected start is inside")
    }

    /** The vote's pending buffer must survive the transport's arbitrary chunking like every row does. */
    @Test
    fun `the correction survives chunk boundaries anywhere`() {
        val text = body(liarRow(15, 22, 20), liarRow(15, 23, 20), liarRow(16, 0, 20))
        listOf(1, 3, 7, 64).forEach { size ->
            val out = parse(text, nowMs = wa12Now, clockPairOffsetMs = panelOffsetMs, chunkSize = size)
            assertEquals(3, out.programmes.size, "chunk size $size lost rows")
            assertEquals(
                (probeDay + 20 * 3600 + 20 * 60) * 1000L,
                out.programmes.first().startMs,
                "chunk size $size broke the correction",
            )
        }
    }
}
