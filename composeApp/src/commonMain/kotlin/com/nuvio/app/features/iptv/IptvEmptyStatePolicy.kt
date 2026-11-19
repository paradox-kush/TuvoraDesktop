package com.nuvio.app.features.iptv

/**
 * What the hub's "Nothing here yet" card offers. Pure so it tests without Compose.
 *
 * Empty used to be a dead end (add-a-playlist only): a slow, half-loaded or mis-parsed playlist looked
 * identical to a truly empty one. Retry is now the primary action (a user action that re-runs the same
 * bounded load, never a loop or timer), and a Retry that comes back empty again says so ("Checked just
 * now, still empty") instead of silently showing the same card.
 */
internal object IptvEmptyStatePolicy {
    data class Decision(val offersRetry: Boolean, val checkedJustNow: Boolean)

    /**
     * [retried]: the viewer tapped Retry on this empty state. [status]: where the category load stands.
     * Idle means the content type is disabled (never fetched), so there is nothing to retry.
     */
    fun decide(retried: Boolean, status: LoadStatus): Decision {
        val settledEmpty = status is LoadStatus.Empty || status is LoadStatus.Loaded
        return Decision(offersRetry = status !is LoadStatus.Idle, checkedJustNow = retried && settledEmpty)
    }
}
