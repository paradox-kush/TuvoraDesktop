package com.nuvio.app.core.contracts

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/** A home-row item reduced to what recommendation logging needs: stable id + raw content type. */
data class HomeRecItem(val itemId: String, val rawContentType: String)

/**
 * Spatial contract (Invariant S): binds home-catalog poster events to recommendation logging.
 *
 * The home catalog UI is shared and must not import the rec subsystem (fork). It hands rec the two
 * primitives it needs — item id + raw content type, plus the row identity — and rec decides what to
 * log and how. No-op default (see [HomeRecAccess]): with nothing registered, clicks pass straight
 * through and rows log nothing, so home renders identically in unit tests and previews.
 */
interface HomeRecBinder {
    /** Log a poster click on the HOME surface. Called immediately before the click delegate runs. */
    fun logRowClick(rowId: String, item: HomeRecItem, itemPosition: Int?)

    /**
     * A Composable that observes the shelf's LazyListState and logs impressions for items that dwell
     * on screen, or null when impressions are not being logged. [itemAt] maps a row index to its
     * item (null past the ends).
     */
    fun rowImpressions(
        rowId: String,
        itemAt: (Int) -> HomeRecItem?,
    ): (@Composable (LazyListState) -> Unit)?
}

object HomeRecAccess {
    private var binder: HomeRecBinder? = null

    fun register(b: HomeRecBinder) {
        binder = b
    }

    /** Null until a fork binder registers — the no-op default. */
    fun current(): HomeRecBinder? = binder

    fun resetForTest() {
        binder = null
    }
}
