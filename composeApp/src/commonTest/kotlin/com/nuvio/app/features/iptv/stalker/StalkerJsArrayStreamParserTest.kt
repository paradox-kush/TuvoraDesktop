package com.nuvio.app.features.iptv.stalker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** B76: get_all_channels is split out of its envelope row by row, from chunks cut anywhere. */
class StalkerJsArrayStreamParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun ids(body: String, chunk: Int): List<String> {
        val p = StalkerJsArrayStreamParser(json) { (it["id"] as? JsonPrimitive)?.contentOrNull }
        body.chunked(chunk).forEach(p::accept)
        return p.finish()
    }

    private val envelope =
        """{"js":{"total_items":3,"max_page_items":14,"names":["a","b"],"meta":{"data":[9]},""" +
            """"data":[{"id":"1","name":"A [HD]","cmd":"ffmpeg http://x/ch/1"},""" +
            """{"id":"2","name":"q\"uote {x}","logo":"l.png"},{"id":"3","name":"C"}]}}"""

    @Test
    fun `rows under js data come out in order from any chunking`() {
        for (size in listOf(1, 2, 3, 7, 64, 10_000)) {
            assertEquals(listOf("1", "2", "3"), ids(envelope, size), "chunk=$size")
        }
    }

    @Test
    fun `a bare js array works too`() {
        assertEquals(listOf("5", "6"), ids("""{"js":[{"id":"5"},{"id":"6"}]}""", 4))
    }

    @Test
    fun `an envelope without rows is empty not an error`() {
        assertEquals(emptyList(), ids("""{"js":null}""", 3))
        assertEquals(emptyList(), ids("""{"js":{"total_items":0}}""", 3))
        assertEquals(emptyList(), ids("", 3))
    }

    @Test
    fun `a body cut inside the array throws`() {
        val p = StalkerJsArrayStreamParser(json) { it }
        p.accept("""{"js":{"data":[{"id":"1"},{"id":""")
        assertFailsWith<IllegalStateException> { p.finish() }
    }

    @Test
    fun `nested data keys deeper in the envelope are not mistaken for the rows`() {
        val rows = ids("""{"js":{"meta":{"data":[{"id":"x"}]},"data":[{"id":"real"}]}}""", 5)
        assertTrue(rows == listOf("real"), "$rows")
    }
}
