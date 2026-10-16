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
}
