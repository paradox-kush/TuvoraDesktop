package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumeStreamPickTest {

    private fun stream(name: String, source: String? = null, addon: String = "A", url: String? = "http://h/$name") =
        StreamItem(name = name, url = url, sourceName = source, addonName = addon, addonId = addon)

    @Test
    fun resume_plays_the_first_stream_the_list_shows() {
        // The list sorts each group's sources alphabetically, so "alpha" is drawn above "Zulu".
        val groups = listOf(
            AddonStreamGroup("A", "A", listOf(stream("z1", source = "Zulu"), stream("a1", source = "alpha"))),
            AddonStreamGroup("B", "B", listOf(stream("b1", addon = "B"))),
        )
        assertEquals("a1", ResumeStreamPick.firstPlayable(groups) { true }?.name)
    }

    @Test
    fun resume_skips_streams_that_cannot_play() {
        val groups = listOf(
            AddonStreamGroup("A", "A", listOf(stream("dead", url = null))),
            AddonStreamGroup("B", "B", listOf(stream("ok", addon = "B"))),
        )
        assertEquals("ok", ResumeStreamPick.firstPlayable(groups) { it.url != null }?.name)
    }

    @Test
    fun nothing_playable_means_no_resume_action() {
        assertNull(ResumeStreamPick.firstPlayable(emptyList()) { true })
        val loading = listOf(AddonStreamGroup("A", "A", emptyList(), isLoading = true))
        assertNull(ResumeStreamPick.firstPlayable(loading) { true })
    }
}
