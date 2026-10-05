package com.nuvio.app.features.library

import com.nuvio.app.core.contracts.GENERIC_LIVE_CHANNEL_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B64 device pass T3: a favourite saved under the new id with the generic "Live TV" launch title won the
 * re-key over the correctly named old one, so Favourites showed "Live TV". The moved item's real name now
 * replaces a placeholder one; a real name already stored is kept.
 */
class LibraryRekeyTest {

    private fun tv(id: String, name: String, logo: String? = null) =
        LibraryItem(id = id, type = "tv", name = name, logo = logo, savedAtEpochMs = 5)

    @Test
    fun aRealNameReplacesThePlaceholderAlreadySavedUnderTheNewId() {
        val placeholder = tv("new", GENERIC_LIVE_CHANNEL_NAME)
        val out = LibraryRekey.upserts(listOf(placeholder), listOf(tv("new", "BBC One", logo = "bbc.png")))
        assertEquals(listOf("BBC One"), out.map { it.name })
        assertEquals(5L, out.single().savedAtEpochMs, "the stored item keeps its place in the favourites order")
        assertEquals("bbc.png", out.single().logo)
    }

    @Test
    fun aBlankNameIsAPlaceholderToo() {
        assertEquals(listOf("BBC One"), LibraryRekey.upserts(listOf(tv("new", " ")), listOf(tv("new", "BBC One"))).map { it.name })
    }

    @Test
    fun aRealNameAlreadySavedIsKept() {
        assertEquals(emptyList(), LibraryRekey.upserts(listOf(tv("new", "BBC One HD")), listOf(tv("new", "BBC One"))))
    }

    @Test
    fun twoOldItemsMovingOntoOneIdKeepTheRealName() {
        val out = LibraryRekey.upserts(emptyList(), listOf(tv("new", GENERIC_LIVE_CHANNEL_NAME), tv("new", "BBC One")))
        assertEquals(listOf("BBC One"), out.map { it.name })
    }
}
