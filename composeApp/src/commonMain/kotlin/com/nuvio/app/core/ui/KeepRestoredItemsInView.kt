package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Keeps an item restored at a lazy list's leading edge in view (see [RestoredItemAnchorPolicy]).
 * Call it in the composition that hands [keys] (in display order, the list's own keys) to the list:
 * the correction is requested from that composition and lands on the list's next measure.
 */
@Composable
fun KeepRestoredItemsInView(state: LazyListState, keys: List<Any>) {
    KeepRestoredItemsInView(
        keys = keys,
        firstVisible = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key to state.firstVisibleItemScrollOffset },
        pinTo = { index, offset -> state.requestScrollToItem(index, offset) },
    )
}

/** Grid flavour of [KeepRestoredItemsInView] (the hub's View-all page). */
@Composable
fun KeepRestoredItemsInView(state: LazyGridState, keys: List<Any>) {
    KeepRestoredItemsInView(
        keys = keys,
        firstVisible = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.key to state.firstVisibleItemScrollOffset },
        pinTo = { index, offset -> state.requestScrollToItem(index, offset) },
    )
}

private class KeysMemo(var keys: List<Any>? = null)

@Composable
private fun KeepRestoredItemsInView(
    keys: List<Any>,
    /** The first visible item's KEY (from the last layout) and its scroll offset. */
    firstVisible: () -> Pair<Any?, Int>,
    pinTo: (index: Int, offset: Int) -> Unit,
) {
    val memo = remember { KeysMemo() }
    val previous = memo.keys
    if (previous == keys) return
    memo.keys = keys
    if (previous == null) return
    // Read the scroll position without subscribing: this must not recompose the list on every scroll.
    val (anchorKey, offset) = Snapshot.withoutReadObservation(firstVisible)
    RestoredItemAnchorPolicy.restoredSlot(previous, keys, anchorKey)?.let { pinTo(it, offset) }
}
