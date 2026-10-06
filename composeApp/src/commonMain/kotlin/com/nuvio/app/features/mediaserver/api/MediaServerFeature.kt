package com.nuvio.app.features.mediaserver.api

import com.nuvio.app.core.contracts.LocalStateCleaner
import com.nuvio.app.core.contracts.LocalStateCleanerRegistry
import com.nuvio.app.core.contracts.ProfileChangeParticipants
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.source.MediaServerItemRegistry
import com.nuvio.app.features.mediaserver.internal.source.MediaServerPlaybackSessions
import com.nuvio.app.features.mediaserver.internal.source.MediaServerSourceRegistrations
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.profiles.MAX_PROFILES

/**
 * What the playlist-sync engine needs of the media-server entries (design 5.3: ONE engine owns the
 * `iptv_playlists` revision counter; media servers add a second row mapper, not a second loop).
 */
class MediaServerSyncBinding(
    /** The active profile's entries - what the user sees and what a push carries. */
    val currentEntries: () -> List<MediaServerEntry>,
    /** Whether the in-memory list may full-replace the server's media rows (a damaged/absent local store must not). */
    val canPushFullReplace: () -> Boolean,
    /** Applies the server's (reconciled) rows as the new local list WITHOUT echoing a push back. */
    val applyFromRemote: (profileId: Int, remote: List<MediaServerEntry>) -> Unit,
)

/**
 * The media-server feature's stable provider factory and composition-root hook (rules doc Rule 1). The one
 * place the wiring files call: [registerLogic] adds the feature to every plural source port, the profile
 * switch fan-out and the sign-out cleaner. Fork-owned; shared code never names it - it only meets the neutral
 * ports in `core/contracts`.
 */
object MediaServerFeature {
    const val SOURCE_NAME = "mediaserver"

    /**
     * Process-init registration (call ONCE, from the logic half of the composition root, so Apple TV runs the
     * same list). [syncSink] is how a local entry change reaches the playlist-sync engine (null = device-local).
     */
    fun registerLogic(syncSink: MediaServerSyncSink?) {
        MediaServerRuntime.installSyncSink(syncSink)
        MediaServerSourceRegistrations.register()
        val runtime = MediaServerRuntime.production
        ProfileChangeParticipants.register { profileIndex ->
            runtime.entryStore.onProfileChanged(profileIndex)
            MediaServerItemRegistry.reset()
            MediaServerPlaybackSessions.reset()
            runtime.homeContributor?.resetForProfile()
        }
        LocalStateCleanerRegistry.register(object : LocalStateCleaner {
            override val name = "Media servers"
            override fun clearLocalState() {
                // Sign-out / account wipe: entries, sign-in tokens and pinned certificates are device state that must not outlive the account.
                runtime.entryStore.clearAll(1..MAX_PROFILES)
                runtime.services.credentials.clearAll()
                MediaServerTrust.shared.clearAll()
                MediaServerItemRegistry.reset()
                MediaServerPlaybackSessions.reset()
                runtime.homeContributor?.resetForProfile()
                runtime.services.notifyCredentialsChanged()
            }
        })
    }

    /** The sync engine's view of the entries. Stable instance semantics: reads the production store. */
    fun syncBinding(): MediaServerSyncBinding {
        val store = MediaServerRuntime.production.entryStore
        return MediaServerSyncBinding(
            currentEntries = { store.current() },
            canPushFullReplace = { store.canPushFullReplace() },
            applyFromRemote = { profileId, remote -> store.applyFromRemote(profileId, remote) },
        )
    }
}
