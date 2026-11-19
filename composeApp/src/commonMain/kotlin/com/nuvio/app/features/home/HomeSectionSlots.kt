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
        order.forEach { id -> blocks[id]?.forEach { block -> scope.block() } }
    }
}
