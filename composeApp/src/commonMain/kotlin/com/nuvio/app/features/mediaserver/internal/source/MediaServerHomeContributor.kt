package com.nuvio.app.features.mediaserver.internal.source

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.ContributedRowDeclaration
import com.nuvio.app.core.contracts.HomeSectionContributor
import com.nuvio.app.features.catalog.CATALOG_PAGE_SIZE
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import com.nuvio.app.features.mediaserver.internal.client.HomeShelves
import com.nuvio.app.features.mediaserver.internal.client.ItemsQuery
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy.Decision
import com.nuvio.app.features.mediaserver.internal.policy.HomeRefreshPolicy.ServerState
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A server's OWN shelves on Home - Continue Watching, Next Up, Recently Added - strictly opt-in per server in
 * Settings (D2): with none enabled this contributes nothing and makes no request. Tuvora's own rows stay the
 * default; items a server row would repeat from Tuvora's Continue Watching are hidden (design 5.7).
 *
 * Rules (design 5.8 + CLAUDE.md recurring-network): called only under Home's own lifecycle - there is no timer
 * here; [HomeRefreshPolicy] gates every fetch (delta: enabled rows only; TTL; 503 `Retry-After` / exponential
 * backoff; an offline server hides its rows and is NOT retried in a loop); section keys are
 * `ms:{type}:{machineId}:{rowId}` - stable across re-login, never containing a user id.
 */
internal class MediaServerHomeContributor(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val nowMs: () -> Long,
    private val tuvoraContinueWatchingIds: () -> Set<String>,
    private val titles: MediaServerRowTitles = ResourceMediaServerRowTitles,
) : HomeSectionContributor {
    override val name: String = "mediaserver"
    private val log = Logger.withTag("MediaServerHome")
    private val lock = SynchronizedObject()
    private val states = mutableMapOf<String, ServerState>()
    private val cache = mutableMapOf<String, List<Pair<MediaServerHomeRow, List<MetaPreview>>>>()
    private val libraryCache = mutableMapOf<String, List<MetaPreview>>()
    private val declaredTitles = mutableMapOf<String, String>()

    /** A websocket `UserDataChanged` / `LibraryChanged`, or one of our own playback reports: the next refresh refetches. */
    fun invalidate(sourceKey: String) = synchronized(lock) {
        states.keys.filter { it.startsWith("$sourceKey:") }.forEach { k -> states[k] = HomeRefreshPolicy.invalidate(states[k] ?: ServerState()) }
    }

    fun resetForProfile() = synchronized(lock) {
        states.clear()
        cache.clear()
        libraryCache.clear()
    }

    /** The server's own refresh state (for a Settings "offline" badge). */
    fun isOffline(serverKey: String): Boolean = synchronized(lock) { states[serverKey]?.let(HomeRefreshPolicy::isOffline) == true }

    override suspend fun sections(forceRefresh: Boolean): List<HomeCatalogSection> {
        prepareDeclaredRows()
        val entries = store.current().filter {
            it.enabled && (it.homeRows.isNotEmpty() || it.homeLibraries.isNotEmpty()) && !it.address.isNullOrBlank() && services.isSignedIn(it)
        }
        return coroutineScope {
            entries.map { entry ->
                async { (if (entry.homeRows.isEmpty()) emptyList() else sectionsFor(entry, forceRefresh)) + librarySections(entry, forceRefresh) }
            }.awaitAll().flatten()
        }
    }

    /**
     * The rows the Home layout settings should list for the servers' current settings - NOT tied to whether a row has
     * items right now (an empty or offline row must stay reorderable / hideable). Synchronous and network-free.
     */
    override fun declaredRows(): List<ContributedRowDeclaration> =
        store.current().filter { it.enabled }.flatMap { entry ->
            val rows = MediaServerHomeRow.entries.filter { it in entry.homeRows }.map { row ->
                ContributedRowDeclaration(rowKey(entry, row), declaredTitle(entry, row), entry.name)
            }
            val libraries = entry.homeLibraries.map { (viewId, name) ->
                ContributedRowDeclaration(libraryKey(entry, viewId), name, entry.name)
            }
            rows + libraries
        }

    private fun declaredTitle(entry: MediaServerEntry, row: MediaServerHomeRow): String =
        synchronized(lock) { declaredTitles["${row.name}|${entry.name}"] } ?: fallbackTitle(row)

    /** English stand-ins until [prepareDeclaredRows] has fetched the localized titles (never resource I/O on a caller's thread). */
    private fun fallbackTitle(row: MediaServerHomeRow): String = when (row) {
        MediaServerHomeRow.CONTINUE_WATCHING -> "Continue Watching"
        MediaServerHomeRow.NEXT_UP -> "Next Up"
        MediaServerHomeRow.RECENTLY_ADDED -> "Recently Added"
    }

    /** Fetches the localized titles of every configured row so [declaredRows] (synchronous) can name them. */
    suspend fun prepareDeclaredRows() {
        store.current().filter { it.enabled }.forEach { entry ->
            entry.homeRows.forEach { row ->
                val key = "${row.name}|${entry.name}"
                if (synchronized(lock) { declaredTitles[key] } == null) {
                    val title = titles.home(row, entry.name)
                    synchronized(lock) { declaredTitles[key] = title }
                }
            }
        }
    }

    private suspend fun librarySections(entry: MediaServerEntry, force: Boolean): List<HomeCatalogSection> =
        coroutineScope {
            entry.homeLibraries.map { (viewId, name) -> async { librarySection(entry, viewId, name, force) } }.awaitAll().filterNotNull()
        }

    private suspend fun librarySection(entry: MediaServerEntry, viewId: String, name: String, force: Boolean): HomeCatalogSection? {
        val stateKey = "${entry.serverKey}|lib:$viewId"
        val now = nowMs()
        val state = synchronized(lock) { states[stateKey] ?: ServerState() }
        val items: List<MetaPreview> = if (HomeRefreshPolicy.shouldFetchList(state, now, force)) {
            val client = services.clientFor(entry) ?: return null
            try {
                val query = HomeRefreshPolicy.rowQuery
                val page = client.items(
                    ItemsQuery(
                        parentId = viewId, includeItemTypes = listOf("Movie", "Series"), sortBy = "DateCreated", sortOrder = "Descending",
                        limit = query.limit, fields = query.fields, collapseBoxSetItems = true, enableTotalRecordCount = query.enableTotalRecordCount,
                    ),
                ).items
                MediaServerItemRegistry.registerAll(page.mapNotNull { MediaServerItemMapper.registered(entry, it) })
                val built = page.mapNotNull { MediaServerItemMapper.preview(entry, it) }.distinctBy { it.id }
                synchronized(lock) {
                    states[stateKey] = HomeRefreshPolicy.afterSuccess(states[stateKey] ?: ServerState(), now)
                    libraryCache[stateKey] = built
                }
                built
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaServerException) {
                val http = e as? MediaServerException.Http
                if (http?.isUnauthorized == true) services.onUnauthorized(entry.serverKey)
                log.w { "library row refresh failed: ${http?.status ?: e::class.simpleName}" }
                val next = synchronized(lock) {
                    HomeRefreshPolicy.afterFailure(states[stateKey] ?: ServerState(), now, http?.status, http?.retryAfterSeconds).also { states[stateKey] = it }
                }
                if (HomeRefreshPolicy.isOffline(next)) emptyList() else synchronized(lock) { libraryCache[stateKey] }.orEmpty()
            }
        } else {
            if (HomeRefreshPolicy.isOffline(state)) emptyList() else synchronized(lock) { libraryCache[stateKey] }.orEmpty()
        }
        if (items.isEmpty()) return null
        return HomeCatalogSection(
            key = libraryKey(entry, viewId),
            title = name,
            subtitle = entry.name,
            addonName = entry.name,
            target = CatalogTarget.Source(entry.sourceKey, "$LIBRARY_PREFIX$viewId", "movie"),
            items = items,
            hasMore = true,
        )
    }

    private suspend fun sectionsFor(entry: MediaServerEntry, force: Boolean): List<HomeCatalogSection> {
        val now = nowMs()
        val state = synchronized(lock) { states[entry.serverKey] ?: ServerState() }
        val decision = HomeRefreshPolicy.decide(entry.homeRows, state, now, force)
        val rows: List<Pair<MediaServerHomeRow, List<MetaPreview>>> = when (decision) {
            is Decision.Skip -> {
                // fresh -> the cached rows; backing off -> the cached rows unless the server counts as offline (rows hidden)
                if (HomeRefreshPolicy.isOffline(state)) return emptyList()
                synchronized(lock) { cache[entry.serverKey] }.orEmpty()
            }
            is Decision.Fetch -> fetch(entry, decision.rows, now)
        }
        return build(entry, rows)
    }

    private suspend fun fetch(entry: MediaServerEntry, wanted: Set<MediaServerHomeRow>, now: Long): List<Pair<MediaServerHomeRow, List<MetaPreview>>> {
        val client = services.clientFor(entry) ?: return emptyList()
        return try {
            val query = HomeRefreshPolicy.rowQuery
            val shelves = client.homeShelves(wanted, query.limit, query.fields)
            val built = rowsOf(entry, shelves, wanted)
            synchronized(lock) {
                states[entry.serverKey] = HomeRefreshPolicy.afterSuccess(states[entry.serverKey] ?: ServerState(), now)
                cache[entry.serverKey] = built
            }
            built
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException) {
            val http = e as? MediaServerException.Http
            if (http?.isUnauthorized == true) services.onUnauthorized(entry.serverKey)
            log.w { "home refresh failed: ${http?.status ?: e::class.simpleName}" }
            val next = synchronized(lock) {
                HomeRefreshPolicy.afterFailure(states[entry.serverKey] ?: ServerState(), now, http?.status, http?.retryAfterSeconds)
                    .also { states[entry.serverKey] = it }
            }
            if (HomeRefreshPolicy.isOffline(next)) emptyList() else synchronized(lock) { cache[entry.serverKey] }.orEmpty()
        }
    }

    private fun rowsOf(entry: MediaServerEntry, shelves: HomeShelves, wanted: Set<MediaServerHomeRow>): List<Pair<MediaServerHomeRow, List<MetaPreview>>> {
        val all = shelves.continueWatching + shelves.nextUp + shelves.recentlyAdded
        MediaServerItemRegistry.registerAll(all.mapNotNull { MediaServerItemMapper.registered(entry, it) })
        fun previews(items: List<com.nuvio.app.features.mediaserver.internal.client.mediabrowser.ItemDto>) =
            items.mapNotNull { MediaServerItemMapper.preview(entry, it) }.distinctBy { it.id }
        return buildList {
            if (MediaServerHomeRow.CONTINUE_WATCHING in wanted) add(MediaServerHomeRow.CONTINUE_WATCHING to previews(shelves.continueWatching))
            if (MediaServerHomeRow.NEXT_UP in wanted) add(MediaServerHomeRow.NEXT_UP to previews(shelves.nextUp))
            if (MediaServerHomeRow.RECENTLY_ADDED in wanted) add(MediaServerHomeRow.RECENTLY_ADDED to previews(shelves.recentlyAdded))
        }
    }

    private suspend fun build(entry: MediaServerEntry, rows: List<Pair<MediaServerHomeRow, List<MetaPreview>>>): List<HomeCatalogSection> {
        val tuvoraIds = tuvoraContinueWatchingIds()
        return rows.filter { (row, _) -> row in entry.homeRows }.mapNotNull { (row, items) ->
            // a server's CW / Next Up repeats what Tuvora's own Continue Watching already shows: hide those cards
            val visible = if (row == MediaServerHomeRow.RECENTLY_ADDED) items
            else items.filterNot { it.id in tuvoraIds }
            if (visible.isEmpty()) return@mapNotNull null
            HomeCatalogSection(
                key = rowKey(entry, row),
                title = titles.home(row, entry.name),
                subtitle = entry.name,
                addonName = entry.name,
                target = CatalogTarget.Source(entry.sourceKey, rowId(row), "movie"),
                items = visible,
                hasMore = row == MediaServerHomeRow.RECENTLY_ADDED,
            )
        }
    }

    override fun ownsSource(sourceKey: String): Boolean = store.current().any { it.sourceKey == sourceKey }

    /** "See all": a bigger page of the row (or of a library). Recently Added pages by `StartIndex`; the shelves are not paged. */
    override suspend fun loadSourcePage(target: CatalogTarget.Source, skip: Int?): CatalogPage {
        val entry = store.current().firstOrNull { it.sourceKey == target.sourceKey && services.isSignedIn(it) }
            ?: return CatalogPage(emptyList(), 0, null)
        val client = services.clientFor(entry) ?: return CatalogPage(emptyList(), 0, null)
        val start = skip ?: 0
        return try {
            val page = when {
                target.listId == rowId(MediaServerHomeRow.RECENTLY_ADDED) -> client.items(
                    ItemsQuery(
                        includeItemTypes = listOf("Movie", "Series"), sortBy = "DateCreated", sortOrder = "Descending",
                        startIndex = start, limit = CATALOG_PAGE_SIZE, fields = "Overview,ProviderIds", collapseBoxSetItems = false,
                    ),
                ).items
                target.listId.startsWith(LIBRARY_PREFIX) -> client.items(
                    ItemsQuery(
                        parentId = target.listId.removePrefix(LIBRARY_PREFIX), includeItemTypes = listOf("Movie", "Series"),
                        sortBy = "SortName", sortOrder = "Ascending", startIndex = start, limit = CATALOG_PAGE_SIZE,
                        fields = "Overview,ProviderIds", collapseBoxSetItems = true,
                    ),
                ).items
                else -> {
                    val row = MediaServerHomeRow.entries.firstOrNull { rowId(it) == target.listId }
                    val shelves = row?.let { client.homeShelves(setOf(it), CATALOG_PAGE_SIZE, HomeRefreshPolicy.ROW_FIELDS) }
                    when (row) {
                        MediaServerHomeRow.CONTINUE_WATCHING -> shelves?.continueWatching
                        MediaServerHomeRow.NEXT_UP -> shelves?.nextUp
                        else -> null
                    }.orEmpty()
                }
            }
            MediaServerItemRegistry.registerAll(page.mapNotNull { MediaServerItemMapper.registered(entry, it) })
            val previews = page.mapNotNull { MediaServerItemMapper.preview(entry, it) }.distinctBy { it.id }
            CatalogPage(previews, page.size, if (page.size >= CATALOG_PAGE_SIZE && target.listId.let { it == rowId(MediaServerHomeRow.RECENTLY_ADDED) || it.startsWith(LIBRARY_PREFIX) }) start + page.size else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException) {
            if ((e as? MediaServerException.Http)?.isUnauthorized == true) services.onUnauthorized(entry.serverKey)
            CatalogPage(emptyList(), 0, null)
        }
    }

    companion object {
        const val LIBRARY_PREFIX = "library:"

        /** `ms:{type}:{machineId}:{rowId}` - stable across a re-login (never carries a user id): the join with Home preferences. */
        fun rowKey(entry: MediaServerEntry, row: MediaServerHomeRow): String = "${MediaServerIds.CONTENT_PREFIX}:${entry.sourceKey}:${rowId(row)}"

        fun libraryKey(entry: MediaServerEntry, viewId: String): String = "${MediaServerIds.CONTENT_PREFIX}:${entry.sourceKey}:$LIBRARY_PREFIX$viewId"

        fun rowId(row: MediaServerHomeRow): String = when (row) {
            MediaServerHomeRow.CONTINUE_WATCHING -> "continue_watching"
            MediaServerHomeRow.NEXT_UP -> "next_up"
            MediaServerHomeRow.RECENTLY_ADDED -> "recently_added"
        }
    }
}
