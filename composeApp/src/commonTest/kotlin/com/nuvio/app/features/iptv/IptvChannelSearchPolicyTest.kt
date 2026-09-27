package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F01: channel search in the guide (and live hits in global search). */
class IptvChannelSearchPolicyTest {

    @Test
    fun every_query_word_must_appear_in_any_order_ignoring_case_and_punctuation() {
        assertTrue(IptvChannelSearchPolicy.matches("bbc hd", "UK: BBC ONE HD"))
        assertTrue(IptvChannelSearchPolicy.matches("one bbc", "UK | BBC-ONE"))
        assertFalse(IptvChannelSearchPolicy.matches("bbc two", "UK: BBC ONE HD"))
    }

    @Test
    fun a_blank_query_matches_nothing() {
        assertFalse(IptvChannelSearchPolicy.matches("  ", "BBC One"))
        assertEquals(emptyList(), IptvChannelSearchPolicy.search(listOf("BBC One"), " ") { it })
    }

    @Test
    fun names_starting_with_the_query_rank_first_then_word_starts_then_the_rest() {
        val names = listOf("CBBC", "UK: BBC One", "BBC Two", "Sky Sports", "ABBC News")
        assertEquals(
            listOf("BBC Two", "UK: BBC One", "CBBC", "ABBC News"),
            IptvChannelSearchPolicy.search(names, "bbc") { it },
        )
    }

    @Test
    fun ties_keep_the_playlist_order() {
        val names = listOf("BBC Two", "BBC One", "BBC Four")
        assertEquals(names, IptvChannelSearchPolicy.search(names, "bbc") { it })
    }

    @Test
    fun letters_outside_ascii_are_matched() {
        assertTrue(IptvChannelSearchPolicy.matches("télé", "FR: Télé Loisirs"))
        assertTrue(IptvChannelSearchPolicy.matches("mbc", "AR: MBC مصر"))
    }
}
