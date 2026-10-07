package com.nuvio.app.features.mediaserver.internal

import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.mediaserver.api.MediaServerSyncSink
import com.nuvio.app.features.mediaserver.internal.client.AuthorityRoutingHttp
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.app.features.mediaserver.internal.source.MediaServerHomeContributor
import com.nuvio.app.features.mediaserver.internal.store.MediaServerCredentialStore
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.mediaserver.internal.store.PlatformEntriesPersistence
import com.nuvio.app.features.mediaserver.internal.store.PlatformSecureTokenStore
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/**
 * The media-server feature's collaborators, assembled once per process (production) or by hand (tests): the
 * entry store, the client services and the clock. Registrations and the screens take what they need from here.
 */
internal class MediaServerRuntime(
    val entryStore: MediaServerEntryStore,
    val services: MediaServerServices,
    val nowMs: () -> Long,
) {
    /** The Home contributor the registrations created (the screens invalidate it after a change). Null until registered. */
    var homeContributor: MediaServerHomeContributor? = null

    companion object {
        private val syncSink = kotlinx.atomicfu.atomic<MediaServerSyncSink?>(null)

        /** The composition root hands over how local changes reach the playlist-sync engine. */
        fun installSyncSink(sink: MediaServerSyncSink?) {
            syncSink.value = sink
        }

        private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }

        val production: MediaServerRuntime by lazy {
            val credentials = MediaServerCredentialStore(PlatformSecureTokenStore)
            val services = MediaServerServices(
                http = AuthorityRoutingHttp(MediaServerTrust.shared),
                credentials = credentials,
                identityProvider = {
                    MediaBrowserClientIdentity(
                        client = "Tuvora",
                        device = currentDeviceClientMetadata().deviceName,
                        deviceId = credentials.deviceId(),
                        version = AppVersionConfig.VERSION_NAME,
                    )
                },
                nowMs = clock,
            )
            val store = MediaServerEntryStore(
                persistence = PlatformEntriesPersistence,
                sink = { syncSink.value },
                activeProfileId = { ProfileRepository.activeProfileId },
                migrateSavedData = { oldPrefix, newPrefix ->
                    LibraryRepository.migrateIdPrefix(oldPrefix, newPrefix)
                    WatchProgressRepository.migrateIdPrefix(oldPrefix, newPrefix)
                    WatchedRepository.migrateIdPrefix(oldPrefix, newPrefix)
                },
            )
            MediaServerRuntime(store, services, clock)
        }
    }
}
