package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.contracts.PlaybackResumeOffer
import com.nuvio.app.core.contracts.PlaybackResumeOfferSource
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.IsoTime
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.PlaybackDecisionPolicy
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException

/**
 * "Resume from server" (D3, design 5.7 / 5.12): with no Tuvora progress the server's position is where playback
 * starts; with a Tuvora record, Tuvora resumes from ITS record and, when the server's own position is newer
 * (the viewer watched elsewhere), the player is told so it can offer the jump. One
 * item fetch at play start (the item carries `UserData`), decided by [PlaybackDecisionPolicy.resumeOffer].
 */
internal class MediaServerResumeOffers(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
) : PlaybackResumeOfferSource {
    override val name: String = "mediaserver"

    override fun handles(videoId: String, providerAddonId: String?): Boolean =
        MediaServerIds.isContentId(videoId) && MediaServerIds.parse(videoId)?.kind.let { it == MediaServerIds.Kind.MOVIE || it == MediaServerIds.Kind.EPISODE }

    override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer? {
        val parsed = MediaServerIds.parse(videoId) ?: return null
        val entry = store.entryByServerKey(parsed.serverKey) ?: return null
        val client = services.clientFor(entry) ?: return null
        val item = try {
            client.item(parsed.itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            return null
        } catch (e: MediaServerException) {
            return null
        } ?: return null
        val data = item.userData ?: return null
        val offer = PlaybackDecisionPolicy.resumeOffer(
            serverPositionMs = data.playbackPositionTicks.takeIf { it > 0 }?.let(PlaybackDecisionPolicy::ticksToMs),
            serverLastPlayedAtMs = IsoTime.parse(data.lastPlayedDate),
            tuvoraPositionMs = tuvoraPositionMs,
            tuvoraUpdatedAtMs = tuvoraUpdatedAtMs,
            durationMs = durationMs ?: item.runTimeTicks?.let(PlaybackDecisionPolicy::ticksToMs),
        ) ?: return null
        return PlaybackResumeOffer(offer.serverPositionMs, autoStart = offer.startAutomatically)
    }
}
