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

    /** Which path runs a native play/pause request (the play button's setPlaybackState event). */
    fun routePlaybackRequest(partySynced: Boolean): Route = if (partySynced) Route.Party else Route.Native

    /** Back (the native X or Escape) while the party panel is open closes the panel, never the player under it. */
    fun backClosesPartyPanel(showPartyPanel: Boolean): Boolean = showPartyPanel

    /** Whether the native controls show the Watch party entry. */
    fun partyEntryVisible(available: Boolean, controlsLocked: Boolean): Boolean = available && !controlsLocked

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

/**
 * Upstream 8633ef741 renders the whole watch-party UI (panel, chat, pill) as HTML inside the native controls
 * (player-ui/party.js). The fork ships its own panel as a window over the video (PlayerSidePanelHost) plus the
 * native status line, so both cannot be live at once: this switch picks the fork's. Flip it to adopt upstream's
 * in-controls UI (then retire the window and the fork's party button/status line).
 */
internal object DesktopPartyUi {
    const val inControlsPanel: Boolean = false
}
