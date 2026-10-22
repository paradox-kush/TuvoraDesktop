package com.nuvio.app.features.player

/**
 * Desktop's player controls are a native layer over the video (PlatformPlayerSurface): it plays, pauses and seeks the
 * engine itself unless the runtime consumes the action. Pure, so the watch-party rules are pinned by tests.
 */
internal object NativePartyControlsPolicy {
    enum class Route { Native, Party }

    private val playbackActions = setOf(
        PlayerControlsAction.TogglePlayback,
        PlayerControlsAction.KeyboardTogglePlayback,
        PlayerControlsAction.SeekBack,
        PlayerControlsAction.KeyboardSeekBack,
        PlayerControlsAction.SeekForward,
        PlayerControlsAction.KeyboardSeekForward,
        PlayerControlsAction.DoubleTapSeekBack,
        PlayerControlsAction.DoubleTapSeekForward,
    )

    /**
     * Which path runs a native control action. In a party, play/pause/seek go through the party-aware runtime paths
     * (publish to the others, honour host-only); left to the native fallback, a desktop member's pause never reaches
     * the party and the drift correction undoes their seek.
     */
    fun routeAction(action: PlayerControlsAction, partySynced: Boolean): Route =
        if (partySynced && action in playbackActions) Route.Party else Route.Native

    /**
     * The runtime did not do what the native play button asked (a host-only guest may not start the party): the
     * button already flipped optimistically, so the controls must be told to show the engine's real state now.
     */
    fun playbackRequestRefused(requestedPlay: Boolean, resultingShouldPlay: Boolean): Boolean =
        requestedPlay != resultingShouldPlay

    /**
     * "Watch with the party" on a media-server title this device has no connection for. The shared path shows an app
     * toast, which on desktop draws under the native video while the player is open; there the player says it
     * through the native notification instead and stays put.
     */
    fun notConnectedNoticeInPlayer(isDesktop: Boolean, notConnected: Boolean): Boolean = isDesktop && notConnected
}
