package com.nuvio.app.features.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Local invalidation only. A successful refresh publishes once; observers never poll a provider. */
data class IptvCatalogKey(val accountId: String, val kind: String? = null)

object IptvCatalogChanges {
    private val revisions = MutableStateFlow<Map<IptvCatalogKey, Long>>(emptyMap())
    val state = revisions.asStateFlow()
    fun changed(accountId: String, kind: String? = null) {
        val key = IptvCatalogKey(accountId, kind)
        revisions.update { it + (key to ((it[key] ?: 0L) + 1L)) }
    }
}
