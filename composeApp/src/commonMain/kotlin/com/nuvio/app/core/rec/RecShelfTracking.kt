package com.nuvio.app.core.rec

import androidx.compose.runtime.Immutable

/**
 * What a shelf needs to declare so its items can be logged as impressions.
 *
 * Passed to `NuvioShelfSection` as an optional parameter: the shelf is generic over its entry
 * type and cannot identify an arbitrary `T` on its own, so the row that owns the data supplies
 * the mapping. Omitting it means that shelf logs nothing, which is the right default for shelves
 * that are not recommendation surfaces (settings pickers, episode strips).
 *
 * [rowId] must be STABLE across recompositions and app launches — it is the join key that makes
 * "this row performs better than that one" answerable. Use the catalogue identity, never a
 * display title or a list index.
 */
@Immutable
data class RecShelfTracking<T>(
    val surface: String,
    val rowId: String,
    /**
     * Position of the row on the page, when the call site knows it. Null where the home list is
     * built by a non-indexed traversal — the schema allows it, and [rowId] carries the identity
     * that training actually joins on. Worth filling in later if row-position bias becomes
     * something we want to model.
     */
    val rowIndex: Int? = null,
    val itemOf: (T) -> RecImpressionItem?,
)
