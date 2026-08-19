package com.nuvio.app.features.player

/**
 * Whether returning to the foreground should rejoin the live edge (a full reload) rather than
 * simply unpausing.
 *
 * Right for live — a backgrounded live stream goes stale and its socket eventually drops. Wrong for
 * a recording (catch-up), where a reload would throw away the viewer's position.
 *
 * Pure player-domain policy: the generic player engine (every platform's PlayerEngine) consults it,
 * so it lives in neutral player code rather than the IPTV catch-up cluster it was factored out of.
 * See features.iptv CatchUpPlayback for the catch-up-vs-live gates that stay IPTV-domain.
 */
object LivePlaybackRejoinPolicy {
    fun rejoinsLiveEdge(streamType: String?, isCatchUpPlayback: Boolean): Boolean =
        !isCatchUpPlayback && streamType?.trim()?.lowercase() == "live"
}
