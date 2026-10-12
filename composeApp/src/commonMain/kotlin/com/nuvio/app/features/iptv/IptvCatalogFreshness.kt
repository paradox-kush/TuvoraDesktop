package com.nuvio.app.features.iptv

/** Local freshness decision. Checking a fresh catalogue never makes a provider request. */
object IptvCatalogFreshness {
    private const val HOUR_MS = 3_600_000L

    fun <T> dueKinds(stamps: Map<T, Long?>, refreshHours: Int, nowMs: Long): List<T> =
        stamps.filterValues { stale(it, refreshHours, nowMs) }.keys.toList()

    fun stale(builtAtMs: Long?, refreshHours: Int, nowMs: Long, fallbackHours: Int = 72): Boolean {
        if (builtAtMs == null || builtAtMs <= 0) return true
        val hours = if (refreshHours > 0) refreshHours else fallbackHours
        val age = nowMs - builtAtMs
        return age < 0 || age >= hours.toLong() * HOUR_MS
    }
}
