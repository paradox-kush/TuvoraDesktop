package com.nuvio.app.features.iptv

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Drives the IPTV hub. Category lists (per account + section) are cached in memory so switching
 * sections/accounts is instant — no reload flash — and category items are fetched lazily, for the
 * rows scrolled into view plus a small bounded lookahead ([prefetchCategory]) so the next rows
 * arrive filled in rather than as shimmer. On launch it kicks a THROTTLED background prefetch of
 * every section's category list so the first switch is already warm; the throttle (a monotonic
 * mark) means rapidly re-foregrounding the app won't hammer the panel.
 */
object XtreamHubRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(XtreamHubUiState())
    val uiState: StateFlow<XtreamHubUiState> = _uiState.asStateFlow()

    // Now/next EPG per live channel, kept separate from uiState so a per-channel fetch doesn't
    // recompose the whole hub. Fetched lazily as channel tiles scroll into view.
    private val _epg = MutableStateFlow<Map<String, ChannelEpg>>(emptyMap())
    val epg: StateFlow<Map<String, ChannelEpg>> = _epg.asStateFlow()
    private val epgFetched = mutableSetOf<String>()

    // (accountId, section) -> category list, each carrying its own lazily-loaded items.
    // Guarded by categoryLock: several item fetches run at once and every one of them rewrites
    // this map from a background dispatcher.
    private val categoryLock = SynchronizedObject()
    private val cache = mutableMapOf<Pair<String, XtreamHubSection>, List<XtreamHubCategory>>()
    private var lastPrefetchMark: TimeMark? = null
    private val REFRESH_TTL = 6.hours

    // --- category-item fetch bounds ---------------------------------------------------
    // A single category can be tens of MB of JSON on a real panel, so the row lookahead must never
    // turn into a fan-out: at most MAX_CONCURRENT_CATEGORY_LOADS responses are ever being parsed at
    // once, and best-effort prefetches are dropped as soon as MAX_OUTSTANDING_CATEGORY_LOADS
    // fetches are already claimed (which also makes prefetch back off by itself while the user
    // flings and the visible rows are hogging the pipe).
    private const val MAX_CONCURRENT_CATEGORY_LOADS = 3
    private const val MAX_OUTSTANDING_CATEGORY_LOADS = 6
    private val categoryLoadGate = Semaphore(MAX_CONCURRENT_CATEGORY_LOADS)

    /**
     * How many categories keep their loaded items in memory at once.
     *
     * [cache] used to only ever grow: nothing but a profile switch or a playlist edit emptied it,
     * so a session that browsed several sections across two playlists retained every item it had
     * ever seen — and each of those items is retained a second time by [XtreamItemRegistry].
     * Past this cap the least-recently-loaded category drops its items and reloads if the user
     * scrolls back to it, which is a cheap category fetch (or a local DB read for M3U).
     */
    private const val MAX_LOADED_CATEGORIES = 40
    private val loadedOrder = ArrayDeque<CategoryKey>()

    /**
     * Categories with a fetch claimed (queued or running). This — not XtreamHubCategory.loading —
     * is the single-flight guard: the lookahead means several rows ask for the same category, and a
     * category-list refresh rebuilds the list with `loading` cleared, so that flag alone can be
     * lost mid-fetch.
     */
    private val inFlightCategories = mutableSetOf<CategoryKey>()

    private data class CategoryKey(
        val accountId: String,
        val section: XtreamHubSection,
        val categoryId: String,
    )

    /** Sync accounts, show the current section (from cache if warm), and prefetch the rest. */
    fun ensureLoaded() {
        XtreamRepository.ensureLoaded()
        // Warm the canonical-EPG mirror (12h TTL, no-op when fresh) — it backs the hub's
        // now/next whenever the panel's own EPG is missing.
        scope.launch { com.nuvio.app.features.epg.EpgMirrorRepository.ensureFresh() }
        val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled }
        val current = _uiState.value
        val selected = current.selectedAccountId?.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull()?.id
        val section = clampSection(accounts.firstOrNull { it.id == selected }, current.section)
        _uiState.update { it.copy(accounts = accounts, selectedAccountId = selected, section = section) }
        if (selected != null) {
            showSection(selected, section)
            maybePrefetch(selected)
        }
    }

    fun selectAccount(accountId: String) {
        if (_uiState.value.selectedAccountId == accountId) return
        val section = clampSection(accountFor(accountId), _uiState.value.section)
        _uiState.update { it.copy(selectedAccountId = accountId, section = section) }
        showSection(accountId, section)
        maybePrefetch(accountId)
    }

    fun selectSection(section: XtreamHubSection) {
        if (_uiState.value.section == section) return
        val accountId = _uiState.value.selectedAccountId ?: return
        _uiState.update { it.copy(section = section) }
        showSection(accountId, section)
    }

    /** Re-fetch the current section's category list after a failed load (error-card retry). */
    fun retryCategories() {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        showSection(accountId, state.section)
    }

    /** Show cached categories instantly, else fetch the (cheap) category list. */
    private fun showSection(accountId: String, section: XtreamHubSection) {
        if (accountFor(accountId)?.typeEnabled(section.contentKey) == false) {
            // Disabled content type: never fetched, nothing shown.
            _uiState.update { it.copy(categories = emptyList(), loadingCategories = false, loadError = false) }
            return
        }
        val cached = cachedCategories(accountId, section)
        if (cached != null) {
            _uiState.update { it.copy(categories = cached, loadingCategories = false, loadError = false) }
            return
        }
        _uiState.update { it.copy(categories = emptyList(), loadingCategories = true, loadError = false) }
        scope.launch { fetchCategoryList(accountId, section) }
    }

    private suspend fun fetchCategoryList(accountId: String, section: XtreamHubSection) {
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId } ?: return
        val client = IptvClient.forAccount(account)   // xtream -> XtreamClient, m3u_url -> M3UClient
        val fresh = when (section) {
            XtreamHubSection.LIVE -> client.liveCategories(account)
            XtreamHubSection.MOVIES -> client.vodCategories(account)
            XtreamHubSection.SERIES -> client.seriesCategories(account)
        }.getOrNull() ?: run {
            // Failed fetch: keep any warm cache, but if there's none the section would otherwise spin
            // forever — surface an error so the user knows the portal is unreachable, not just slow.
            if (isCurrent(accountId, section) && cachedCategories(accountId, section) == null) {
                _uiState.update { it.copy(loadingCategories = false, loadError = true) }
            }
            return
        }
        // Merge: carry over already-loaded items for categories that still exist.
        val merged = synchronized(categoryLock) {
            val previous = cache[accountId to section].orEmpty().associateBy { it.id }
            val next = fresh.map { cat ->
                val old = previous[cat.id]
                XtreamHubCategory(cat.id, cat.name, items = old?.items ?: emptyList(), loaded = old?.loaded ?: false)
            }
            cache[accountId to section] = next
            next
        }
        if (isCurrent(accountId, section)) {
            _uiState.update { it.copy(categories = merged, loadingCategories = false, loadError = false) }
        }
    }

    /** Lazily fetch one category's items (called when its row first composes). */
    fun loadCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = false)
    }

    /**
     * Warm a category the user hasn't scrolled to yet, so its row lands with real posters and names
     * instead of shimmer. Best-effort by design: dropped whenever enough fetches are already
     * outstanding, so flinging through hundreds of categories can never queue unbounded work.
     */
    fun prefetchCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = true)
    }

    private fun requestCategory(categoryId: String, prefetch: Boolean) {
        val state = _uiState.value
        val accountId = state.selectedAccountId ?: return
        val section = state.section
        val category = cachedCategories(accountId, section)?.firstOrNull { it.id == categoryId } ?: return
        if (category.loaded) return
        val key = CategoryKey(accountId, section, categoryId)
        // Claim the fetch atomically: a visible row always gets one, a prefetch only while the pipe
        // has room.
        val claimed = synchronized(categoryLock) {
            when {
                key in inFlightCategories -> false
                prefetch && inFlightCategories.size >= MAX_OUTSTANDING_CATEGORY_LOADS -> false
                else -> inFlightCategories.add(key)
            }
        }
        if (!claimed) return
        updateCategory(accountId, section, categoryId) { it.copy(loading = true) }
        scope.launch {
            var completed = false
            try {
                categoryLoadGate.withPermit {
                    val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }
                    val client = account?.let { IptvClient.forAccount(it) }
                    // Register the whole category in ONE batch. This loop is the browse hot path —
                    // a category can be tens of thousands of items, and registering per-item took
                    // the registry lock once per item.
                    val items = if (account == null || client == null) emptyList() else when (section) {
                        XtreamHubSection.LIVE -> client.liveChannels(account, categoryId).getOrDefault(emptyList()).let { rows ->
                            XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedChannel(accountId, it) })
                            rows.map { it.toMetaPreview(accountId) }
                        }
                        XtreamHubSection.MOVIES -> client.vodMovies(account, categoryId).getOrDefault(emptyList()).let { rows ->
                            XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedMovie(accountId, it) })
                            rows.map { it.toMetaPreview(accountId) }
                        }
                        XtreamHubSection.SERIES -> client.series(account, categoryId).getOrDefault(emptyList()).let { rows ->
                            XtreamItemRegistry.registerAll(rows.map { XtreamItemRegistry.resolvedSeries(accountId, it) })
                            rows.map { it.toMetaPreview(accountId) }
                        }
                    }
                    updateCategory(accountId, section, categoryId) { it.copy(items = items, loaded = true, loading = false) }
                    noteLoadedAndEvict(key)
                    completed = true
                }
            } finally {
                synchronized(categoryLock) { inFlightCategories.remove(key) }
                // Never strand a row as permanently "loading" if the fetch was cancelled.
                if (!completed) updateCategory(accountId, section, categoryId) { it.copy(loading = false) }
            }
        }
    }

    /** Background-refresh every section's category list on launch, throttled to once per TTL. */
    private fun maybePrefetch(accountId: String) {
        val mark = lastPrefetchMark
        if (mark != null && mark.elapsedNow() < REFRESH_TTL) return
        lastPrefetchMark = TimeSource.Monotonic.markNow()
        scope.launch {
            val account = accountFor(accountId)
            for (section in XtreamHubSection.entries) {
                if (account?.typeEnabled(section.contentKey) == false) continue  // disabled type: skip fetch
                fetchCategoryList(accountId, section)
            }
        }
    }

    /** Keep the shown section one the account actually has enabled. */
    private fun clampSection(account: XtreamAccount?, wanted: XtreamHubSection): XtreamHubSection {
        if (account == null || account.typeEnabled(wanted.contentKey)) return wanted
        return XtreamHubSection.entries.firstOrNull { account.typeEnabled(it.contentKey) } ?: wanted
    }

    private fun accountFor(accountId: String?): XtreamAccount? =
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }

    /** Lazily fetch now/next EPG for a live channel (called when its tile scrolls into view). */
    fun ensureEpg(contentId: String) {
        if (!epgFetched.add(contentId)) return
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return
        if (parsed.kind != XtreamKind.LIVE) return
        val streamId = parsed.id.toIntOrNull() ?: return
        val account = XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId } ?: return
        scope.launch {
            // get_short_epg returns current + upcoming, so the nowPlaying (or first) entry is "now".
            // When the panel has nothing (the common case on real panels — Starshare fills 6% of
            // epg_channel_id), fall back to the mirrored canonical EPG via the channel mapping.
            val listings = IptvClient.forAccount(account).shortEpg(account, streamId).getOrDefault(emptyList())
                .ifEmpty {
                    runCatching {
                        com.nuvio.app.features.epg.EpgMirrorRepository
                            .nowNextProgrammes(account.id, streamId, com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs())
                    }.getOrDefault(emptyList())
                }
            if (listings.isEmpty()) return@launch
            val nowIndex = listings.indexOfFirst { it.nowPlaying }.takeIf { it >= 0 } ?: 0
            val now = listings.getOrNull(nowIndex)?.title?.ifBlank { null }
            val next = listings.getOrNull(nowIndex + 1)?.title?.ifBlank { null }
            if (now != null || next != null) {
                _epg.update { it + (contentId to ChannelEpg(now = now, next = next)) }
            }
        }
    }

    /**
     * Marks [key] most-recently-loaded and drops the items of anything past [MAX_LOADED_CATEGORIES].
     * The eviction happens OUTSIDE the lock because [updateCategory] takes it itself.
     */
    private fun noteLoadedAndEvict(key: CategoryKey) {
        val evicted = synchronized(categoryLock) {
            loadedOrder.remove(key)
            loadedOrder.addLast(key)
            val out = ArrayList<CategoryKey>()
            while (loadedOrder.size > MAX_LOADED_CATEGORIES) out.add(loadedOrder.removeFirst())
            out
        }
        for (k in evicted) {
            updateCategory(k.accountId, k.section, k.categoryId) {
                it.copy(items = emptyList(), loaded = false, loading = false)
            }
        }
    }

    fun resetForProfile() {
        synchronized(categoryLock) { cache.clear(); loadedOrder.clear() }
        lastPrefetchMark = null
        epgFetched.clear()
        _epg.value = emptyMap()
        _uiState.value = XtreamHubUiState()
    }

    private fun isCurrent(accountId: String, section: XtreamHubSection): Boolean =
        _uiState.value.selectedAccountId == accountId && _uiState.value.section == section

    private fun cachedCategories(accountId: String, section: XtreamHubSection): List<XtreamHubCategory>? =
        synchronized(categoryLock) { cache[accountId to section] }

    private fun updateCategory(
        accountId: String,
        section: XtreamHubSection,
        categoryId: String,
        transform: (XtreamHubCategory) -> XtreamHubCategory,
    ) {
        val key = accountId to section
        val updated = synchronized(categoryLock) {
            val current = cache[key]
            if (current == null) {
                null
            } else {
                val next = current.map { if (it.id == categoryId) transform(it) else it }
                cache[key] = next
                next
            }
        } ?: return
        if (isCurrent(accountId, section)) _uiState.update { it.copy(categories = updated) }
    }
}
