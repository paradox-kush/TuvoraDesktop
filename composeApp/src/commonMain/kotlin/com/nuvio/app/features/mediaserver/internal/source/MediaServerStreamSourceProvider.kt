package com.nuvio.app.features.mediaserver.internal.source

import co.touchlab.kermit.Logger
import com.nuvio.app.core.contracts.PlaybackPlayMethod
import com.nuvio.app.core.contracts.StreamSourceGroup
import com.nuvio.app.core.contracts.StreamSourceProvider
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.PlaybackInfoRequest
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserDialect
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserUrls
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaSourceDto
import com.nuvio.app.features.mediaserver.internal.policy.MediaServerIds
import com.nuvio.app.features.mediaserver.internal.policy.PlaybackDecisionPolicy
import com.nuvio.app.features.mediaserver.internal.store.MediaServerEntryStore
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.coroutines.CancellationException

/**
 * The media-server lane of the stream-source port (design 5.5): a server's own movie/episode resolves to ONE
 * deferred stream - `ms-deferred:{serverKey}|{itemId}|{mediaSourceId}` - and the real play URL is minted at
 * pick time from the server's PlaybackInfo, through [PlaybackDecisionPolicy]. The list never holds a token or
 * a playable URL. The matched lane (a TMDB title found on a server) is P3: [matchSourceGroups] is empty until
 * then, so nothing about it is reachable yet.
 */
internal class MediaServerStreamSourceProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
) : StreamSourceProvider {
    private val log = Logger.withTag("MediaServerStreamSource")

    override fun isHandledId(videoId: String?): Boolean = MediaServerIds.isContentId(videoId)

    /** Media-server links are minted fresh at play time and never cached; the single-use-link rule is Stalker's. */
    override fun isStalkerSource(videoId: String): Boolean = false

    override fun directStreamItem(videoId: String): StreamItem? {
        val item = MediaServerItemRegistry.get(videoId) ?: return null
        val entry = store.entryByServerKey(item.serverKey) ?: return null
        val source = item.sources.firstOrNull()
        val label = source?.label ?: "Direct play"
        // Emby authenticates a player request by header; Jellyfin's direct stream needs none (design 5.5).
        val headers = tokenHeaders(entry)
        return StreamItem(
            name = label,
            title = item.name,
            url = MediaServerIds.deferredUrl(item.serverKey, item.itemId, source?.id),
            addonName = entry.name,
            addonId = MediaServerIds.DIRECT_GROUP_ID,
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
            ),
        )
    }

    private fun tokenHeaders(entry: com.nuvio.app.features.mediaserver.api.MediaServerEntry): Map<String, String>? {
        val dialect = MediaBrowserDialect.of(entry.type)
        val header = dialect.extraTokenHeader ?: return null
        val token = services.credentials.token(entry.serverKey) ?: return null
        return mapOf(header to token)
    }

    // The matched lane is P3 (design 7): no match sources yet.
    override fun matchSourceGroups(type: String): List<StreamSourceGroup> = emptyList()

    override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> = emptyList()

    override fun isMatchSourceId(providerAddonId: String): Boolean = providerAddonId.startsWith(MediaServerIds.MATCH_GROUP_PREFIX)

    override fun isDeferredUrl(url: String?): Boolean = MediaServerIds.isDeferredUrl(url)

    /**
     * Mints the play URL. [forceMint] means the previous attempt FAILED (the player's credential-refresh gate
     * re-asks): direct play is then treated as failed and the server's transcode is chosen. A revoked token
     * (401/403) drops the session so Settings shows "sign in again" - and returns null (no mint loop).
     */
    override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? {
        val deferred = MediaServerIds.parseDeferred(url) ?: return null
        val entry = store.entryByServerKey(deferred.serverKey) ?: return null
        val address = entry.address?.takeIf { it.isNotBlank() } ?: return null
        val client = services.clientFor(entry) ?: return null
        return try {
            val negotiation = client.playbackInfo(deferred.itemId, PlaybackInfoRequest(mediaSourceId = deferred.mediaSourceId, forceTranscode = forceMint))
            val chosen = negotiation.sources.firstOrNull { s -> deferred.mediaSourceId != null && s.id.equals(deferred.mediaSourceId, ignoreCase = true) }
                ?: negotiation.sources.firstOrNull()
                ?: return null
            val decision = PlaybackDecisionPolicy.decide(chosen.toFacts(), userBitrateCap = null, directPlayFailed = forceMint)
            val minted = when (val plan = decision.plan) {
                is PlaybackDecisionPolicy.Plan.StaticStream ->
                    MediaBrowserUrls.directStream(address, deferred.itemId, plan.mediaSourceId ?: chosen.id, chosen.container, negotiation.playSessionId)
                is PlaybackDecisionPolicy.Plan.ServerUrl -> MediaBrowserUrls.resolve(address, plan.pathOrUrl)
                is PlaybackDecisionPolicy.Plan.NotPlayable -> return null
            }
            MediaServerPlaybackSessions.record(
                MediaServerPlaybackSessions.Session(
                    serverKey = deferred.serverKey,
                    itemId = deferred.itemId,
                    mediaSourceId = chosen.id,
                    playSessionId = negotiation.playSessionId,
                    playMethod = decision.method ?: PlaybackPlayMethod.DIRECT_PLAY,
                ),
            )
            minted
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            log.w { "mint failed: HTTP ${e.status}" }
            null
        } catch (e: MediaServerException) {
            log.w { "mint failed: ${e::class.simpleName}" }
            null
        }
    }

    private fun MediaSourceDto.toFacts() = PlaybackDecisionPolicy.SourceFacts(
        id = id,
        protocol = protocol,
        container = container,
        supportsDirectPlay = supportsDirectPlay,
        supportsDirectStream = supportsDirectStream,
        supportsTranscoding = supportsTranscoding,
        directStreamUrl = directStreamUrl,
        transcodingUrl = transcodingUrl,
    )
}
