package com.nuvio.app.features.home

import androidx.compose.foundation.lazy.LazyListScope

/**
 * Collects the Home list's section blocks while the list content is built, then emits them in the
 * order [HomeSectionOrderPolicy] resolved. A block is only stored, never run, until [emit], so the
 * Home screen can declare its sections in code order and still draw them in the user's order.
 * Ids with no block (a section with nothing to show) and ids not in the order are skipped.
 */
internal class HomeSectionSlots {
    private val blocks = mutableMapOf<String, MutableList<LazyListScope.() -> Unit>>()

    fun add(id: String, block: LazyListScope.() -> Unit) {
        blocks.getOrPut(id) { mutableListOf() }.add(block)
    }

    fun emit(scope: LazyListScope, order: List<String>) {
        // The lazy list may re-run its content lambda without the screen recomposing; blocks that
        // stayed behind would be emitted again and crash on a duplicate item key. Emit once, then reset.
        val pending = blocks.toMap()
        blocks.clear()
        order.forEach { id -> pending[id]?.forEach { block -> scope.block() } }
    }
}
