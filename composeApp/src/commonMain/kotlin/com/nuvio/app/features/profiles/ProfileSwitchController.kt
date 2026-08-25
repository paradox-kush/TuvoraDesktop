package com.nuvio.app.features.profiles

import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Fork-owned orchestration for an in-app profile switch: the awaited
 * `switch -> warm -> pull` pipeline plus a **single-flight guard**.
 *
 * ## Why this exists
 * Every in-app switch site (the native OS profile switcher, the in-app profile sheet, and the gate
 * worker) needs the exact same three awaited steps in the exact same order:
 * 1. [ProfileRepository.switchToProfile] — the ~30-way `onProfileChanged` fan-out, run off the main
 *    thread under [ProfileRepository]'s own mutex.
 * 2. [warmProfileBoundRepositories] — reload the now-active profile's repositories.
 * 3. [SyncManager.pullAllForProfile] — pull that profile's synced state from the backend.
 *
 * Keeping this pipeline (and its imports) in a fork-owned file — rather than inline in `App.kt` —
 * keeps the shared `App.kt` spine merge-clean when upstream is pulled in: the orchestration is not a
 * conflict surface. There is no fork/shared duality here (a profile switch means the same thing on
 * every platform), so this is a plain object, not a `core/contracts` port.
 *
 * ## The single-flight guard
 * The in-app profile sheet used to launch a fresh coroutine per tap with no guard, so repeated taps
 * would **stack concurrent switch/warm/pull pipelines**: the first `finally` cleared the loading
 * overlay while later pipelines were still running, producing overlay flicker, a concurrent
 * [SyncManager.pullAllForProfile] stampede, and inconsistent post-switch state.
 *
 * [runSwitch] closes that hole with a non-suspending [Mutex.tryLock]: while one switch is in flight
 * the mutex is held, so a second concurrent call returns `false` immediately and runs none of its
 * steps. [switchingTo] exposes the target being switched to (or `null` when idle) so the UI drives
 * its loading overlay off observed state instead of a per-call-site boolean.
 */
object ProfileSwitchController {

    private val mutex = Mutex()

    private val _switchingTo = MutableStateFlow<Int?>(null)

    /** The profile index currently being switched to, or `null` when no switch is in flight. */
    val switchingTo: StateFlow<Int?> = _switchingTo.asStateFlow()

    /**
     * Runs the awaited `switch -> warm -> pull` pipeline for [profileIndex], guarded single-flight.
     *
     * @param syncOnEnter when `true`, pulls the profile's synced state after warming; when `false`,
     *   the switch stops after warming (e.g. an unauthenticated enter has nothing to pull).
     * @return `true` if this call performed the switch; `false` if another switch was already in
     *   flight and this call was ignored.
     */
    suspend fun switch(profileIndex: Int, syncOnEnter: Boolean): Boolean =
        runSwitch(
            profileIndex = profileIndex,
            switchProfile = { ProfileRepository.switchToProfile(profileIndex) },
            warm = { warmProfileBoundRepositories() },
            pull = { if (syncOnEnter) SyncManager.pullAllForProfile(profileIndex) },
        )

    /**
     * The single-flight core, with each side effect injected so it is unit-testable with fakes.
     *
     * If the mutex cannot be locked immediately a switch is already running: return `false` and run
     * nothing. Otherwise publish [profileIndex] to [switchingTo], run the three steps in order, and
     * always clear [switchingTo] and release the mutex in `finally` (so a cancelled switch — e.g. a
     * superseding native emission under `collectLatest` — still unlocks for the next one).
     *
     * `internal` so `commonTest` can drive the guard directly with fake suspend lambdas.
     */
    internal suspend fun runSwitch(
        profileIndex: Int,
        switchProfile: suspend () -> Unit,
        warm: suspend () -> Unit,
        pull: suspend () -> Unit,
    ): Boolean {
        if (!mutex.tryLock()) return false
        try {
            _switchingTo.value = profileIndex
            switchProfile()
            warm()
            pull()
            return true
        } finally {
            _switchingTo.value = null
            mutex.unlock()
        }
    }

    /**
     * Reloads the now-active profile's repositories. On Mobile only the switch pipeline warms, so
     * this is private there; Desktop **also** pre-warms these once at startup on runtime ownership
     * (`App.kt` `LaunchedEffect(ownsAppRuntime)`), so the entry point is `internal` here for that one
     * extra same-module caller. `internal`, like [runSwitch] — never a foreign-module surface.
     */
    internal suspend fun warmProfileBoundRepositories() {
        withContext(Dispatchers.Default) {
            AddonRepository.initialize()
            CollectionRepository.initialize()
            ContinueWatchingPreferencesRepository.ensureLoaded()
            DownloadsRepository.ensureLoaded()
            EpisodeReleaseNotificationsRepository.ensureLoaded()
            HomeCatalogSettingsRepository.snapshot()
            LibraryRepository.ensureLoaded()
            P2pSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.ensureLoaded()
            TraktAuthRepository.ensureLoaded()
            TraktSettingsRepository.ensureLoaded()
            WatchedRepository.ensureLoaded()
            WatchProgressRepository.ensureLoaded()
            CollectionSyncService.startObserving()
            ProfileSettingsSync.startObserving()
            // Warm the IPTV catalog indexes off the critical path so the first
            // play/search doesn't pay the full-catalog download on demand.
            com.nuvio.app.core.contracts.IptvCatalogAccess.catalog.warmUpMatchIndexes(startDelayMs = 10_000)
        }
    }
}
