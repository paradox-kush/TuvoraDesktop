package com.nuvio.app.core.deeplink

/** Where a tapped provider setup link sends the app, decided apart from navigation so it is testable. */
internal data class SetupLinkRoute(
    /** The settings page to open (a `SettingsPageRequest` name). */
    val settingsPage: String,
    /** True when the link arrived over a screen that hides the settings shell (player, stream list, detail). */
    val leaveToTabsFirst: Boolean,
)

internal object SetupLinkRoutePolicy {
    const val PREVIEW_PAGE = "IptvSetupPreview"
    const val ADD_PLAYLIST_PAGE = "IptvAddPlaylist"

    /**
     * @param isGuest the person has no real account yet (local-only): they see the code on the Add Playlist
     *   page, where they are asked before being taken to sign-in.
     * @param onTabs the visible route is already the tab shell.
     * @param canPopToTabs this platform keeps the route stack here, so the shell can be reached by popping
     *   (native iOS navigation owns its stack in UIKit).
     */
    fun decide(isGuest: Boolean, onTabs: Boolean, canPopToTabs: Boolean): SetupLinkRoute =
        SetupLinkRoute(
            settingsPage = if (isGuest) ADD_PLAYLIST_PAGE else PREVIEW_PAGE,
            leaveToTabsFirst = !onTabs && canPopToTabs,
        )
}
