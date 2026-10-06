package com.nuvio.app.core.contracts

import kotlinx.coroutines.CancellationException

/** "You were further along elsewhere": the position the player may offer to jump to. */
data class PlaybackResumeOffer(val positionMs: Long)

/**
 * A source that keeps its own resume position and can say when it is NEWER than Tuvora's (a media server the
 * viewer also watches from another app - D3). The player asks once when playback of that source starts; nothing
 * about the answer is applied silently: the viewer is offered a jump and stays where Tuvora's own record put them
 * unless they take it. One request at most (the source answers from the item it already fetches), never a poll.
 */
interface PlaybackResumeOfferSource {
    val name: String

    /** Cheap, synchronous ownership check - plays of other sources are never asked about. */
    fun handles(videoId: String, providerAddonId: String?): Boolean

    /**
     * [tuvoraPositionMs] / [tuvoraUpdatedAtMs]: Tuvora's own record for this video (null = none); [durationMs] the
     * playing file's length when known. Null = nothing to offer (including on any failure).
     */
    suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer?
}

object PlaybackResumeOfferRegistry {
    private val sources = NamedRegistry<PlaybackResumeOfferSource>("PlaybackResumeOfferSource")

    fun register(source: PlaybackResumeOfferSource) = sources.register(source.name, source)

    val isEmpty: Boolean get() = sources.isEmpty

    fun handles(videoId: String, providerAddonId: String?): Boolean = sources.all.any { it.handles(videoId, providerAddonId) }

    /** The first source that owns the item and has something to offer; a source that fails offers nothing. */
    suspend fun offerFor(
        videoId: String,
        providerAddonId: String?,
        tuvoraPositionMs: Long?,
        tuvoraUpdatedAtMs: Long?,
        durationMs: Long?,
    ): PlaybackResumeOffer? {
        for (source in sources.all) {
            if (!source.handles(videoId, providerAddonId)) continue
            try {
                source.offer(videoId, tuvoraPositionMs, tuvoraUpdatedAtMs, durationMs)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // an offer is a courtesy: its failure must never reach playback
            }
        }
        return null
    }

    internal fun resetForTest() = sources.resetForTest()
}
