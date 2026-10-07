package com.nuvio.app.features.home

/** The card Home shows when no addon supplies rows (and no collection renders). */
internal enum class HomeNoAddonsCard {
    /** Full builds: "No active addons". */
    NoActiveAddons,

    /** Store builds hide addons, so point at IPTV setup: "No content yet — add your IPTV playlist". */
    AddIptvPlaylist,

    /** Nothing to suggest — show no card. */
    None,
}

/**
 * UX38: store builds told the user to add an IPTV playlist even after they had added one (IPTV
 * content lives in the IPTV tab, not in Home's addon rows, so Home stays row-less). Once any
 * playlist exists that hint is wrong; the card is dropped instead.
 */
internal object HomeNoAddonsCardPolicy {
    fun card(addonsEnabled: Boolean, hasAnyIptvPlaylist: Boolean): HomeNoAddonsCard =
        when {
            addonsEnabled -> HomeNoAddonsCard.NoActiveAddons
            hasAnyIptvPlaylist -> HomeNoAddonsCard.None
            else -> HomeNoAddonsCard.AddIptvPlaylist
        }
}

/**
 * Whether Home is the "no add-ons" screen (the card, no rows). A row a source contributes (a media server's) is
 * content too: a household that uses only a server has no add-ons and must still see its rows.
 */
internal fun isNoAddonsHome(hasActiveAddons: Boolean, hasRenderableCollectionRows: Boolean, hasContributedOrCatalogSections: Boolean): Boolean =
    !hasActiveAddons && !hasRenderableCollectionRows && !hasContributedOrCatalogSections
