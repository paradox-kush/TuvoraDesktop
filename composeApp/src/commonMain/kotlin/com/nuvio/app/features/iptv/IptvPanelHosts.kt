package com.nuvio.app.features.iptv

import com.nuvio.app.features.streams.PlaybackTlsPolicy
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The hosts the user's IPTV providers live on: every account's own base URL and its backup servers, plus the hosts of the
 * stream URLs the IPTV lanes handed out this session (an M3U line or a Stalker create_link can point at any host). Feeds the
 * player's TLS policy: only these hosts may be retried without certificate verification. Bounded (LRU) so it cannot grow
 * without limit.
 */
internal object IptvPanelHosts {
    private const val MAX_NOTED = 512
    private val lock = SynchronizedObject()
    private val noted = LinkedHashSet<String>()

    fun isPanelHost(host: String): Boolean {
        val h = PlaybackTlsPolicy.normalize(host) ?: return false
        if (synchronized(lock) { h in noted }) return true
        return hostsOf(XtreamRepository.uiState.value.accounts).contains(h)
    }

    /** Remember the host of a stream URL an IPTV lane produced. */
    fun note(url: String?) {
        val host = hostOf(url) ?: return
        synchronized(lock) {
            noted.remove(host)
            noted.add(host)
            while (noted.size > MAX_NOTED) noted.remove(noted.first())
        }
    }

    /** Pure: the hosts of [accounts] (base URL + backup URLs), normalized. */
    fun hostsOf(accounts: List<XtreamAccount>): Set<String> =
        accounts.flatMap { listOf(it.baseUrl) + it.backupUrls }.mapNotNull(::hostOf).toSet()

    internal fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val withScheme = if ("://" in url) url else "http://$url"
        return runCatching { Url(withScheme).host }.getOrNull()?.let(PlaybackTlsPolicy::normalize)
    }

    internal fun clearNotedForTest() = synchronized(lock) { noted.clear() }
}
