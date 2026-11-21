package com.nuvio.app.features.home

import androidx.compose.foundation.lazy.LazyListScope
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionSlotsTest {
    private fun scopeRecording(emitted: MutableList<String>): LazyListScope =
        object : LazyListScope {
            override fun item(key: Any?, contentType: Any?, content: @androidx.compose.runtime.Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
                emitted += key.toString()
            }
            override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @androidx.compose.runtime.Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit) {
                repeat(count) { emitted += key?.invoke(it).toString() }
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun stickyHeader(key: Any?, contentType: Any?, content: @androidx.compose.runtime.Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) = Unit
        }

    @Test
    fun `re-running the list content on one slots instance never repeats a key`() {
        val slots = HomeSectionSlots()
        val emitted = mutableListOf<String>()
        // The lazy list can re-run its content lambda without the screen recomposing.
        repeat(3) {
            slots.add("cw") { item(key = "home_continue_watching") {} }
            slots.emit(scopeRecording(emitted), listOf("cw"))
        }
        assertEquals(3, emitted.size, "one key per run")
        assertEquals(listOf("home_continue_watching"), emitted.distinct())
        // within a single run the key must appear once
        val single = mutableListOf<String>()
        val fresh = HomeSectionSlots()
        fresh.add("cw") { item(key = "home_continue_watching") {} }
        fresh.emit(scopeRecording(single), listOf("cw"))
        fresh.add("cw") { item(key = "home_continue_watching") {} }
        fresh.emit(scopeRecording(single), listOf("cw"))
        assertEquals(2, single.size, "second run emits only its own block, not the first run's again")
    }
}
