package com.nuvio.app.features.announcements.internal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.Announcements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State holder for announcements. All decisions are in [AnnouncementPolicy]; this only loads the
 * cache, asks the policy, fetches, and persists. A failed fetch is silent and keeps the cache (and
 * leaves the fetch time unstamped, so the next home visit retries).
 */
internal class AnnouncementsRepository(
    private val fetch: suspend () -> List<Announcement>,
    private val store: AnnouncementStore,
    private val nowMs: () -> Long,
) : Announcements {

    private val log = Logger.withTag("Announcements")
    private val mutex = Mutex()
    private var loaded = false
    private var items: List<Announcement> = emptyList()
    private var dismissed: Set<String> = emptySet()
    private var lastFetchedAtMs: Long? = null

    private val _visible = MutableStateFlow<Announcement?>(null)
    override val visible: StateFlow<Announcement?> = _visible.asStateFlow()

    override suspend fun refreshIfDue() {
        mutex.withLock {
            ensureLoaded()
            if (!AnnouncementPolicy.shouldFetch(lastFetchedAtMs, nowMs())) return
            val fresh = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.d(e) { "announcements fetch failed; keeping cache" }
                return
            }
            items = fresh
            lastFetchedAtMs = nowMs()
            // Forget dismissals for announcements the server no longer serves — bounded storage.
            dismissed = dismissed.filterTo(mutableSetOf()) { id -> fresh.any { it.id == id } }
            store.save(KEY_ITEMS, AnnouncementCodec.encode(fresh))
            store.save(KEY_LAST_FETCHED_AT, lastFetchedAtMs.toString())
            store.save(KEY_DISMISSED, AnnouncementCodec.encodeIds(dismissed))
            publish()
        }
    }

    override fun dismiss(id: String) {
        // Non-suspending on purpose (UI intent): state is updated synchronously so the card hides
        // in the same frame. Reads/writes of the small sets are main-thread UI intents plus the
        // mutex-guarded refresh; a lost race only re-shows a card once, never corrupts storage.
        if (!loaded) ensureLoaded()
        dismissed = dismissed + id
        store.save(KEY_DISMISSED, AnnouncementCodec.encodeIds(dismissed))
        publish()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        items = AnnouncementCodec.decodeWire(store.load(KEY_ITEMS))
        dismissed = AnnouncementCodec.decodeIds(store.load(KEY_DISMISSED))
        lastFetchedAtMs = store.load(KEY_LAST_FETCHED_AT)?.toLongOrNull()
        publish()
    }

    private fun publish() {
        _visible.value = AnnouncementPolicy.pick(items, dismissed)
    }

    companion object {
        const val KEY_ITEMS = "announcements_cache_v1"
        const val KEY_LAST_FETCHED_AT = "announcements_last_fetched_at_ms"
        const val KEY_DISMISSED = "announcements_dismissed_ids"

        fun platformDefault(): AnnouncementsRepository = AnnouncementsRepository(
            fetch = { fetchAnnouncementsFromSupabase() },
            store = PlatformAnnouncementStore,
            nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        )
    }
}
