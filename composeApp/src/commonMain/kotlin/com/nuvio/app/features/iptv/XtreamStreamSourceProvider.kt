package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.StreamSourceGroup
import com.nuvio.app.core.contracts.StreamSourceProvider
import com.nuvio.app.features.iptv.match.XtreamStreamSource
import com.nuvio.app.features.streams.StreamItem

/**
 * Fork-side [StreamSourceProvider]: the Xtream/Stalker stream lane that used to live inline in the
 * shared streams repositories. Behaviour is identical to the code it replaced — same direct-id
 * resolution, same Stalker "never serve the cache" rule (the caller nulls the direct item for a
 * Stalker source), same enabled-account match filter, same per-account XtreamStreamSource search.
 * The account is carried across the firewall only as an opaque groupId and resolved back here.
 */
internal object XtreamStreamSourceProvider : StreamSourceProvider {
    override fun isHandledId(videoId: String?): Boolean = XtreamItemRegistry.isXtreamId(videoId)

    override fun isStalkerSource(videoId: String): Boolean =
        XtreamItemRegistry.parseId(videoId)?.let { parsed ->
            XtreamRepository.uiState.value.accounts
                .firstOrNull { it.id == parsed.accountId }
                ?.sourceType == SOURCE_TYPE_STALKER
        } ?: false

    override fun directStreamItem(videoId: String): StreamItem? =
        XtreamItemRegistry.streamItemFor(videoId)

    override fun matchSourceGroups(type: String): List<StreamSourceGroup> {
        if (type != "movie" && type != "series") return emptyList()
        XtreamRepository.ensureLoaded()
        return XtreamRepository.uiState.value.accounts
            .filter {
                it.enabled && (it.sourceType == SOURCE_TYPE_XTREAM || it.sourceType == SOURCE_TYPE_STALKER)
            }
            .map { StreamSourceGroup(XtreamStreamSource.groupId(it), it.name) }
    }

    override suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val accountId = sourceId.removePrefix(XtreamStreamSource.GROUP_ID_PREFIX)
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
            ?: return emptyList()
        return XtreamStreamSource.streamsFor(account, type, videoId, season, episode)
    }

    override fun isMatchSourceId(providerAddonId: String): Boolean =
        providerAddonId.startsWith(XtreamStreamSource.GROUP_ID_PREFIX)

    override fun isDeferredUrl(url: String): Boolean = XtreamStreamSource.isDeferred(url)

    override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? =
        XtreamStreamSource.resolveDeferredUrl(url, forceMint = forceMint)
}
