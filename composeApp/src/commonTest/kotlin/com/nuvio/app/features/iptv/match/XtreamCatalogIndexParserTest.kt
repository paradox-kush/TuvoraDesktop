package com.nuvio.app.features.iptv.match

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bulk-list reader that replaced a whole-catalog `parseToJsonElement`, which needed the
 * entire ~27 MB response in memory at once and OOM'd the match-index build on a real provider.
 *
 * Chunk boundaries are the interesting part: the transport splits wherever it likes (the Android
 * reader caps a newline-free document at 1 MB), so every case here is also replayed one
 * character at a time, which is the worst boundary placement possible.
 */
class XtreamCatalogIndexParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ids(vararg chunks: String): List<Int> {
        val parser = XtreamCatalogIndexParser(json, map = { o -> o["id"]?.jsonPrimitive?.intOrNull })
        chunks.forEach(parser::accept)
        return parser.finish()
    }

    /** Same document fed one character at a time — the harshest possible chunking. */
    private fun idsByChar(doc: String): List<Int> = ids(*doc.map(Char::toString).toTypedArray())

    @Test
    fun splitsElementsRegardlessOfWhereChunksFall() {
        val doc = """[{"id":1},{"id":2},{"id":3}]"""
        assertEquals(listOf(1, 2, 3), ids(doc))
        assertEquals(listOf(1, 2, 3), idsByChar(doc))
        // a boundary mid-key, mid-number and mid-punctuation
        assertEquals(listOf(1, 2, 3), ids("""[{"i""", """d":1},{"id":2""", """},{"id":3}]"""))
    }

    @Test
    fun handlesNestedObjectsArraysAndPrettyPrinting() {
        // Panels ship nested info blocks; braces inside them must not end an element early.
        val doc = """
            [
              {"id": 1, "info": {"cast": ["a", "b"], "meta": {"deep": [1, 2]}}},
              {"id": 2, "backdrop": []}
            ]
        """.trimIndent()
        assertEquals(listOf(1, 2), ids(doc))
        assertEquals(listOf(1, 2), idsByChar(doc))
    }

    @Test
    fun bracesAndBracketsInsideStringsDoNotEndAnElement() {
        // Real titles carry these: "Movie [2019] {Extended}", plus escaped quotes.
        val parser = XtreamCatalogIndexParser(json, map = { o -> o["name"]?.jsonPrimitive?.contentOrNull })
        parser.accept("""[{"name":"A [2019] {x}, and \"quoted\""},{"name":"B"}]""")
        assertEquals(listOf("""A [2019] {x}, and "quoted"""", "B"), parser.finish())
    }

    @Test
    fun escapedBackslashBeforeQuoteStillClosesTheString() {
        // "C:\\" ends the string at the following quote — the backslash is escaped, not escaping.
        val parser = XtreamCatalogIndexParser(json, map = { o -> o["name"]?.jsonPrimitive?.contentOrNull })
        parser.accept("""[{"name":"back\\"},{"name":"next"}]""")
        assertEquals(listOf("""back\""", "next"), parser.finish())
    }

    @Test
    fun escapeSequenceSplitAcrossChunksIsNotLost() {
        // The backslash and the quote it escapes land in different chunks — scanner state has to
        // survive the boundary or the string is read as closed and the element falls apart.
        val parser = XtreamCatalogIndexParser(json, map = { o -> o["name"]?.jsonPrimitive?.contentOrNull })
        parser.accept("[{\"name\":\"a\\")          // ends on the backslash
        parser.accept("\"b\"},{\"name\":\"c\"}]")  // opens on the quote it escapes
        assertEquals(listOf("a\"b", "c"), parser.finish())
    }

    @Test
    fun oneMalformedElementCostsOnlyThatElement() {
        // The old whole-document decode threw on the first bad byte and lost the ENTIRE catalog,
        // so the provider's index silently never built. Now a bad row is just a missing title.
        assertEquals(listOf(1, 3), ids("""[{"id":1},{"id":,,},{"id":3}]"""))
    }

    @Test
    fun emptyArrayYieldsNothing() {
        assertEquals(emptyList(), ids("[]"))
        assertEquals(emptyList(), ids("  [  ]  "))
    }

    @Test
    fun nonObjectElementsAreSkipped() {
        // Matches the old `mapNotNull { it as? JsonObject }`.
        assertEquals(listOf(2), ids("""[1,"x",null,{"id":2}]"""))
    }

    @Test
    fun anObjectWhereTheCatalogShouldBeIsAFailureNotAnEmptyCatalog() {
        // A panel that errors mid-session answers {"user_info":…}. Read as an empty catalog that
        // would look like "the provider has no movies" — and an empty list is accepted on a first
        // build, so it would stick.
        assertFailsWith<IllegalStateException> { ids("""{"user_info":{"auth":0}}""") }
        assertFailsWith<IllegalStateException> { ids("""<html>Blocked</html>""") }
    }

    @Test
    fun aTruncatedBodyFailsInsteadOfReturningAPartialCatalog() {
        // The caller diffs this list against the stored index and DELETES what is missing, so a
        // half-downloaded catalog must not come back as a complete one.
        val err = assertFailsWith<IllegalStateException> { ids("""[{"id":1},{"id":2}""") }
        assertTrue(err.message!!.contains("mid-array"), "unexpected: ${err.message}")
        assertFailsWith<IllegalStateException> { ids("") }
    }

    @Test
    fun feedsRealCatalogRowsThroughTheExistingItemParsers() {
        // End to end on the shapes the panels actually send, including the field-type chaos the
        // loose parsers exist to absorb (tmdb as int on one row, quoted string on the next).
        val vodParser = XtreamCatalogIndexParser<IndexedItem>(json, map = { o -> parseVod(o) })
        vodParser.accept(
            """[{"stream_id":1,"name":"Dune (2021)","tmdb":"438631","container_extension":"mkv"},"""
        )
        vodParser.accept("""{"stream_id":"2","name":"Arrival","tmdb":329865},{"name":"No id"}]""")

        val items = vodParser.finish()
        assertEquals(2, items.size, "the id-less row is dropped by the item parser")
        assertEquals(438631, items[0].tmdb, "tmdb arrived as a quoted string")
        assertEquals(2021, items[0].year, "year comes off the title")
        assertEquals("mkv", items[0].ext)
        assertEquals(2, items[1].sid, "stream_id arrived as a quoted string")
        assertEquals(329865, items[1].tmdb)
    }

    /** Mirrors XtreamClient.parseVodIndexItem, which is private to the iptv package. */
    private fun parseVod(o: JsonObject): IndexedItem? {
        val id = o["stream_id"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
            ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return IndexedItem(
            sid = id,
            name = name,
            year = TitleNormalizer.yearOf(name),
            tmdb = o["tmdb"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
                ?.takeIf { it > 0 },
            ext = o["container_extension"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            poster = null,
        )
    }
}
