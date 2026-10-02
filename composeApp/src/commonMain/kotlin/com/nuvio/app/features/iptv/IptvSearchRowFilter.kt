package com.nuvio.app.features.iptv

/**
 * UX44: providers pad their lists with heading rows — "==== Sky Germany ====", "##### UK SPORTS #####" —
 * that are not channels or titles. They match search words ("sky") and then sit in the results as
 * dead rows, so search leaves them out. Pure.
 *
 * Deliberately conservative — a real row must never be dropped: a divider is a name made only of
 * decoration (`=`, `-`, `#`, `*`), or one that both STARTS and ENDS with a run of at least two of
 * them. "4K | Sky Sports", "#1 Movie", "M*A*S*H", "-- Sky News" and "Sky News --" are all real rows.
 */
internal object IptvSearchRowFilter {

    private const val DECORATION = "=-#*"
    private const val MIN_RUN = 2

    fun isDivider(name: String): Boolean {
        val s = name.trim()
        if (s.length < MIN_RUN) return false
        val lead = s.takeWhile { it in DECORATION }.length
        if (lead < MIN_RUN) return false
        if (s.all { it in DECORATION || it == ' ' }) return true
        return s.takeLastWhile { it in DECORATION }.length >= MIN_RUN
    }

    fun <T> withoutDividers(items: List<T>, nameOf: (T) -> String): List<T> =
        items.filterNot { isDivider(nameOf(it)) }
}
