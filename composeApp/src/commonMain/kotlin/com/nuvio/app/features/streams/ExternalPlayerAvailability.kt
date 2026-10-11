package com.nuvio.app.features.streams

import com.nuvio.app.features.servers.ServerStreams

/**
 * Whether a stream may be handed to an external player ("Open in", Infuse-style callbacks). A media-server stream plays
 * from a per-session URL (a transcode session, a play session id, a token in the query) that only this app's player and
 * progress reporting understand, so it never leaves the app. Pure; the same rule as TV's ExternalPlayerAvailability.
 */
internal object ExternalPlayerAvailability {
    /** A listed or prepared stream. */
    fun canHandOff(stream: StreamItem?): Boolean = stream != null && stream.serverTarget == null

    /** The stream currently playing, known only by its provider group id. */
    fun canHandOffProvider(providerAddonId: String?): Boolean = !ServerStreams.isServerSourceId(providerAddonId)

    /**
     * Whether [stream] should be routed to the external player: the user's setting (or an explicit "open externally")
     * AND the stream may leave the app AND nothing forced the internal player.
     */
    fun routeExternally(
        stream: StreamItem,
        externalPlaybackReady: Boolean,
        forceExternal: Boolean = false,
        forceInternal: Boolean = false,
    ): Boolean = !forceInternal && canHandOff(stream) && (forceExternal || externalPlaybackReady)
}
