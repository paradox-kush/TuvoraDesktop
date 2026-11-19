package com.nuvio.app.features.iptv.stalker

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StalkerCategoryReplyTest {
    private fun parse(json: String) = StalkerCategoryReply.parse(Json.parseToJsonElement(json))

    @Test
    fun `a JSON array parses as before and drops the All row`() {
        val cats = parse("""[{"id":"*","title":"All"},{"id":"7","title":"News"},{"id":"8","name":"Sport"}]""")
        assertEquals(listOf("7" to "News", "8" to "Sport"), cats.map { it.id to it.name })
    }

    @Test
    fun `an empty array is a genuine empty list`() {
        assertEquals(emptyList(), parse("[]").map { it.id })
    }

    @Test
    fun `a reply that is not an array is a failure not an empty list`() {
        assertFailsWith<StalkerMalformedReplyException> { parse("""{"error":"auth"}""") }
        assertFailsWith<StalkerMalformedReplyException> { parse(""""<html>blocked</html>"""") }
        assertFailsWith<StalkerMalformedReplyException> { parse("42") }
    }
}
