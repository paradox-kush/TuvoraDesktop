package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.IptvSearchProvider
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One search entry per signed-in server (design 5.4 "Search"): the registry merges its rows next to IPTV's.
 * A server's rows are `Movies` and `TV Shows` hits of `/Items?SearchTerm=` (the one route both dialects
 * share; Emby has no `/Search`), each carrying its own enabled gate so a signed-out or disabled server is
 * never queried and adds nothing to the request key.
 */
internal class MediaServerSearchProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val titles: MediaServerRowTitles = ResourceMediaServerRowTitles,
) : IptvSearchProvider {
    private fun searchable(): List<MediaServerEntry> =
        store.current().filter { it.enabled && !it.address.isNullOrBlank() && services.isSignedIn(it) }

    override fun isEnabled(): Boolean = searchable().isNotEmpty()

    override suspend fun search(query: String): List<HomeCatalogSection> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return coroutineScope {
            searchable().map { entry -> async { searchOne(entry, term) } }.awaitAll().flatten()
        }
    }

    private suspend fun searchOne(entry: MediaServerEntry, term: String): List<HomeCatalogSection> {
        val client = services.clientFor(entry) ?: return emptyList()
        val hits = try {
            client.search(term, SEARCH_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            return emptyList()
        } catch (e: MediaServerException) {
            return emptyList()
        }
        MediaServerItemRegistry.registerAll(hits.mapNotNull { MediaServerItemMapper.registered(entry, it) })
        val previews = hits.mapNotNull { MediaServerItemMapper.preview(entry, it) }
        val movies = previews.filter { it.type == "movie" }
        val series = previews.filter { it.type == "series" }
        return buildList {
            if (movies.isNotEmpty()) add(row(entry, "search:movies", "movie", titles.searchMovies(entry.name), movies))
            if (series.isNotEmpty()) add(row(entry, "search:series", "series", titles.searchSeries(entry.name), series))
        }
    }

    private fun row(entry: MediaServerEntry, listId: String, contentType: String, title: String, items: List<com.nuvio.app.features.home.MetaPreview>) =
        HomeCatalogSection(
            // Section keys never contain the user id: a re-login must not orphan the viewer's choices.
            key = "${MediaServerIds.CONTENT_PREFIX}:${entry.sourceKey}:$listId",
            title = title,
            subtitle = entry.name,
            addonName = entry.name,
            target = CatalogTarget.Source(entry.sourceKey, listId, contentType),
            items = items,
        )

    /** Enabled + signed-in servers and the credential state, as a digest - never a credential. Null while nothing is searchable. */
    override fun sourceSignature(): String? = signatureOf(searchable())

    override fun sourceSignatureChanges(): Flow<String?> =
        combine(store.entries, services.credentialVersion, services.expiredSessions) { _, _, _ -> sourceSignature() }.distinctUntilChanged()

    private fun signatureOf(entries: List<MediaServerEntry>): String? =
        entries.takeIf { it.isNotEmpty() }?.joinToString("|") { MediaServerIds.hash(it.serverKey + (it.address ?: "")) }

    private companion object {
        const val SEARCH_LIMIT = 20
    }
}
