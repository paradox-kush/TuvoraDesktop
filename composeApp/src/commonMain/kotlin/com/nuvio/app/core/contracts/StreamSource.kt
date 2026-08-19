package com.nuvio.app.core.contracts

import com.nuvio.app.features.streams.StreamItem

/** One IPTV match source (an enabled account), identified by an opaque id the fork maps back. */
data class StreamSourceGroup(val sourceId: String, val addonName: String)

/**
 * Firewall port for IPTV stream resolution, consumed by the shared streams repositories. The fork
 * owns all Xtream/Stalker access; the shared repo orchestrates with neutral results only — StreamItem
 * plus opaque source ids. The account is encoded inside [StreamSourceGroup.sourceId] and resolved
 * back fork-side, so no fork type crosses the firewall. No-op default (see [StreamSourceAccess]):
 * not-handled / empty everywhere, so a build without IPTV simply resolves nothing here.
 */
interface StreamSourceProvider {
    /** True when [videoId] is a namespaced IPTV id (VOD/live) that resolves to one direct stream. */
    fun isHandledId(videoId: String?): Boolean

    /** True when [videoId]'s account is a Stalker portal (single-use links → never serve the cache). */
    fun isStalkerSource(videoId: String): Boolean

    /** The registered direct stream item for [videoId], or null on a registry miss. */
    fun directStreamItem(videoId: String): StreamItem?

    /** One match source per enabled Xtream/Stalker account, for TMDB [type] ("movie"/"series"). */
    fun matchSourceGroups(type: String): List<StreamSourceGroup>

    /** Resolve the streams for a single [matchSourceGroups] entry. */
    suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem>

    /** True when [providerAddonId] identifies a match source (a [matchSourceGroups] entry's id). */
    fun isMatchSourceId(providerAddonId: String): Boolean

    /** True when [url] is a deferred (not-yet-minted) IPTV play URL. */
    fun isDeferredUrl(url: String): Boolean

    /** Mint the real play URL for a deferred [url], or null. [forceMint] bypasses static-cmd reuse. */
    suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String?
}

object StreamSourceAccess {
    private val noOp = object : StreamSourceProvider {
        override fun isHandledId(videoId: String?) = false
        override fun isStalkerSource(videoId: String) = false
        override fun directStreamItem(videoId: String): StreamItem? = null
        override fun matchSourceGroups(type: String) = emptyList<StreamSourceGroup>()
        override suspend fun resolveMatchStreams(
            sourceId: String,
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
        ) = emptyList<StreamItem>()
        override fun isMatchSourceId(providerAddonId: String) = false
        override fun isDeferredUrl(url: String) = false
        override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? = null
    }
    private var provider: StreamSourceProvider? = null

    fun register(p: StreamSourceProvider) {
        provider = p
    }

    /** The registered provider, or a no-op until IPTV registers. */
    fun current(): StreamSourceProvider = provider ?: noOp

    fun resetForTest() {
        provider = null
    }
}
