package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** B68 — a mutation id is reused only for the exact payload it was minted for. */
class PlaylistMutationIdPolicyTest {

    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    private val ab = PlaylistMutationIdPolicy.fingerprint(listOf(acc("A"), acc("B")), deleteAll = false)

    @Test
    fun `the same payload keeps its id so a lost-response retry dedups`() {
        val again = PlaylistMutationIdPolicy.fingerprint(listOf(acc("A"), acc("B")), deleteAll = false)
        assertEquals("m-1", PlaylistMutationIdPolicy.idFor("m-1", ab, again) { "fresh" })
    }

    @Test
    fun `a different payload gets a fresh id`() {
        val abc = PlaylistMutationIdPolicy.fingerprint(listOf(acc("A"), acc("B"), acc("C")), deleteAll = false)
        assertEquals("fresh", PlaylistMutationIdPolicy.idFor("m-1", ab, abc) { "fresh" })
    }

    @Test
    fun `an id stored without its fingerprint is never reused`() {
        assertEquals("fresh", PlaylistMutationIdPolicy.idFor("m-1", null, ab) { "fresh" })
    }

    @Test
    fun `no stored id mints one`() {
        assertEquals("fresh", PlaylistMutationIdPolicy.idFor(null, null, ab) { "fresh" })
    }

    @Test
    fun `the fingerprint covers field edits order and the delete-all flag`() {
        assertNotEquals(ab, PlaylistMutationIdPolicy.fingerprint(listOf(acc("A", name = "Renamed"), acc("B")), deleteAll = false))
        assertNotEquals(ab, PlaylistMutationIdPolicy.fingerprint(listOf(acc("B"), acc("A")), deleteAll = false))
        assertNotEquals(
            PlaylistMutationIdPolicy.fingerprint(emptyList(), deleteAll = false),
            PlaylistMutationIdPolicy.fingerprint(emptyList(), deleteAll = true),
        )
    }
}
