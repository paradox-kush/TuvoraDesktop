package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

class ServedStreamTypesTest {
    private fun account(id: String, types: Set<String>, enabled: Boolean = true) = XtreamAccount(
        id = id, name = id, baseUrl = "http://$id", username = "u", password = "p",
        enabled = enabled, contentTypes = types,
    )

    @Test
    fun `enabled accounts contribute the movie and series types they serve`() {
        val types = servedStreamTypesOf(listOf(
            account("a", setOf(CONTENT_TYPE_LIVE, CONTENT_TYPE_MOVIES)),
            account("b", setOf(CONTENT_TYPE_SERIES)),
        ))
        assertEquals(setOf("movie", "series"), types, "movies + series across accounts")
    }

    @Test
    fun `disabled and live-only accounts serve nothing`() {
        val types = servedStreamTypesOf(listOf(
            account("a", setOf(CONTENT_TYPE_MOVIES, CONTENT_TYPE_SERIES), enabled = false),
            account("b", setOf(CONTENT_TYPE_LIVE)),
        ))
        assertEquals(emptySet(), types, "no playable types")
    }
}
