package com.nuvio.app.features.search

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * UX15: Search kept showing the old IPTV rows after the viewer changed a playlist's content settings
 * (content types, categories, hidden channels or groups), because the request key only recorded
 * whether IPTV was on, so the same query was "reused" against stale IPTV results. KMP twin of
 * NuvioTV's `IptvSearchRefreshPolicy`.
 *
 * Delta-shaped: a settings change only changes what the IPTV lane returns, so normally only the IPTV
 * rows are fetched again and the add-on catalogs (network) are left alone. The whole search re-runs
 * only when the IPTV lane appears or disappears (first playlist enabled, last one disabled), which
 * can change the empty state. Nothing changed means no request at all.
 */
internal object IptvSearchRefreshPolicy {

    enum class Action { REUSE, REFRESH_IPTV_ROWS, RUN_SEARCH }

    /**
     * A search request: [search] is everything except IPTV's source set (query, settings, add-on
     * catalogs, whether IPTV is on); [iptvSignature] is that source set (null = no IPTV lane).
     */
    data class RequestKey(val search: String, val iptvSignature: String?)

    /** What to do for [next] when [shown] is the request whose results are on screen (null = none). */
    fun decide(next: RequestKey, shown: RequestKey?, forceRefresh: Boolean): Action = when {
        forceRefresh || shown == null || next.search != shown.search -> Action.RUN_SEARCH
        next.iptvSignature == shown.iptvSignature -> Action.REUSE
        (next.iptvSignature == null) != (shown.iptvSignature == null) -> Action.RUN_SEARCH
        else -> Action.REFRESH_IPTV_ROWS
    }

    /** Coalesces a burst of playlist-setting toggles into one refresh. */
    const val SOURCE_CHANGE_DEBOUNCE_MS = 500L

    /** The source-set changes a shown search reacts to: distinct, and settled for [debounceMs]. */
    @OptIn(FlowPreview::class)
    fun refreshTicks(signatures: Flow<String?>, debounceMs: Long = SOURCE_CHANGE_DEBOUNCE_MS): Flow<String?> =
        signatures.distinctUntilChanged().debounce(debounceMs)
}
