package com.nuvio.app.core.contracts

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Which hosts are an IPTV provider's panel or stream hosts (Xtream / Stalker / M3U accounts), as the player's TLS policy
 * needs to know. IPTV panels very often serve a self-signed or mismatched certificate, so a failed handshake to THEIR hosts
 * may be retried without verification; every other host (media servers, add-ons, catalogs, CDNs) is validated strictly.
 * Features register a predicate from the composition root (no fork reference here: same shape as [OwnSourcePolicy]).
 */
object PlaybackTrustHosts {
    // Re-registering a name replaces it (the wiring is idempotent; tests re-run the composition root in one process).
    private val panelHostPredicates = atomic<Map<String, (String) -> Boolean>>(emptyMap())

    fun registerPanelHostPredicate(name: String, predicate: (String) -> Boolean) {
        require(name.isNotBlank()) { "panel host predicate name must not be blank" }
        panelHostPredicates.update { it + (name to predicate) }
    }

    /** True when [host] (no port) belongs to a registered IPTV provider. */
    fun isPanelHost(host: String): Boolean = panelHostPredicates.value.values.any { it(host) }
}
