package com.nuvio.app.features.home

/**
 * How Continue Watching is laid out on the home screen (F04). Pure.
 *
 * By default movies and series share one "Continue Watching" row in watch order, the way the TV app
 * and most streaming apps show it; viewers asked for that over the separate Movies and Series rows.
 * Splitting by type is a setting. Live channels never join these rows: a channel has no resume point,
 * so recently watched channels keep their own Live TV row.
 */
internal object ContinueWatchingRowsPolicy {

    /** One row; a null [title] means the default "Continue Watching". */
    data class Row<T>(val title: String?, val items: List<T>)

    fun <T> rows(
        items: List<T>,
        splitByType: Boolean,
        isLive: (T) -> Boolean,
        isSeries: (T) -> Boolean,
    ): List<Row<T>> {
        val resumable = items.filterNot(isLive)
        val rows = if (splitByType) {
            listOf(Row("Movies", resumable.filterNot(isSeries)), Row("Series", resumable.filter(isSeries)))
        } else {
            listOf(Row(null, resumable))
        }
        return rows.filter { it.items.isNotEmpty() }
    }
}
