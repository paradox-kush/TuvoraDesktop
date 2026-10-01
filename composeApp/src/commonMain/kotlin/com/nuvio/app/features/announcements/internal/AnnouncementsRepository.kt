package com.nuvio.app.features.announcements.internal

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.Announcements
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State holder for announcements. All decisions are in [AnnouncementPolicy] (fetching) and
 * [AnnouncementVisibilityPolicy] (who sees what); this only loads the cache, asks the policies,
 * fetches, and persists. A failed fetch is silent and keeps the cache (and leaves the fetch time
 * unstamped, so the next home visit retries).
 *
 * [signIn] is who is signed in, as the visibility policy needs it. Until [followSignIn] reads it the
 * viewer counts as still resolving, so a policy notice is held back rather than shown and then pulled.
 */
internal class AnnouncementsRepository(
    private val fetch: suspend () -> List<Announcement>,
    private val store: AnnouncementStore,
    private val nowMs: () -> Long,
    private val signIn: Flow<AnnouncementSignIn> = flowOf(AnnouncementSignIn.NoAccount),
) : Announcements {

    private val log = Logger.withTag("Announcements")
    private val mutex = Mutex()
    private var loaded = false
    private var items: List<Announcement> = emptyList()
    private var dismissed: Set<String> = emptySet()
    private var lastFetchedAtMs: Long? = null
    private var installFirstSeenAtMs: Long = 0L
    private var currentSignIn: AnnouncementSignIn = AnnouncementSignIn.Resolving

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

    override suspend fun followSignIn() {
        signIn.collect { next ->
            if (!loaded) ensureLoaded()
            currentSignIn = next
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
        val storedLastFetched = store.load(KEY_LAST_FETCHED_AT)
        lastFetchedAtMs = storedLastFetched?.toLongOrNull()
        val storedFirstSeen = store.load(KEY_INSTALL_FIRST_SEEN)?.toLongOrNull()
        installFirstSeenAtMs = AnnouncementVisibilityPolicy.installFirstSeenAtMs(
            storedMs = storedFirstSeen,
            // Any state written by an earlier version means this install was in use before now.
            hasEarlierState = storedLastFetched != null || store.load(KEY_ITEMS) != null ||
                store.load(KEY_DISMISSED) != null,
            nowMs = nowMs(),
        )
        if (storedFirstSeen == null) store.save(KEY_INSTALL_FIRST_SEEN, installFirstSeenAtMs.toString())
        publish()
    }

    private fun publish() {
        _visible.value = AnnouncementVisibilityPolicy.pick(
            items = items,
            dismissedIds = dismissed,
            viewer = AnnouncementViewer(currentSignIn, installFirstSeenAtMs),
        )
    }

    companion object {
        const val KEY_ITEMS = "announcements_cache_v1"
        const val KEY_LAST_FETCHED_AT = "announcements_last_fetched_at_ms"
        const val KEY_DISMISSED = "announcements_dismissed_ids"
        const val KEY_INSTALL_FIRST_SEEN = "announcements_install_first_seen_ms"

        fun platformDefault(): AnnouncementsRepository = AnnouncementsRepository(
            fetch = { fetchAnnouncementsFromSupabase() },
            store = PlatformAnnouncementStore,
            nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            signIn = AuthRepository.state.map { state ->
                AnnouncementVisibilityPolicy.signInFrom(state, sessionAccountRecord())
            },
        )

        /** The signed-in user's sign-up facts from the local Supabase session (no network). */
        private fun sessionAccountRecord(): AnnouncementAccountRecord? = runCatching {
            SupabaseProvider.client.auth.currentUserOrNull()?.let { user ->
                AnnouncementAccountRecord(
                    userId = user.id,
                    createdAtMs = user.createdAt?.toEpochMilliseconds(),
                    acceptedTermsVersion = AnnouncementVisibilityPolicy.termsVersionFrom(user.userMetadata),
                )
            }
        }.getOrNull()
    }
}
