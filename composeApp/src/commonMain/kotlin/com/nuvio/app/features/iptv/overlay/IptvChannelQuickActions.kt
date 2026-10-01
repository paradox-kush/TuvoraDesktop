package com.nuvio.app.features.iptv.overlay

/**
 * UX36 + UX73: what a long-press on a live channel offers. One gesture, one meaning: the Live TV
 * guide and the browse hub show the same menu (favourite, then hide), where the guide used to hide
 * silently and the hub used to favourite. Pure, so it tests without the overlay store or the UI.
 */
internal object IptvChannelQuickActionsPolicy {

    enum class Action { ADD_FAVORITE, REMOVE_FAVORITE, HIDE }

    /** The channel a Hide, and the Undo after it, act on: its canon-v1 identity and its playlist. */
    data class HideTarget(val entityId: String, val playlistId: String?)

    /** Null when the channel has no durable identity here, so there is nothing a hide could key on. */
    fun hideTarget(entityId: String?, playlistId: String?): HideTarget? =
        entityId?.takeIf { it.isNotBlank() }?.let { HideTarget(it, playlistId) }

    /**
     * The hub's Favorites and Recent Channels rails are the viewer's own lists, which a hide does
     * not filter (UX37 keeps that open), so hiding from one would look like it did nothing: Hide is
     * offered only on provider and custom-group rows.
     */
    fun hubHideTarget(target: HideTarget?, inPersonalRail: Boolean): HideTarget? =
        if (inPersonalRail) null else target

    fun menu(isFavorite: Boolean, hideTarget: HideTarget?): List<Action> = buildList {
        add(if (isFavorite) Action.REMOVE_FAVORITE else Action.ADD_FAVORITE)
        if (hideTarget != null) add(Action.HIDE)
    }

    /**
     * The hub's rows without the channels the viewer hid. Rows are filtered when they are fetched,
     * but a hide made afterwards (from the guide, the hub, or the website) must drop the channel from
     * rows already on screen, and an Undo must bring it back without a re-fetch — so the hide is
     * also applied whenever the rows are shown. A card whose identity is unknown ([entityOf] null)
     * stays. Returns [items] itself when nothing in it is hidden.
     */
    fun <T> visibleInHub(items: List<T>, overlay: Map<String, ChannelOverlay>, entityOf: (T) -> String?): List<T> {
        if (items.isEmpty() || overlay.values.none { it.hidden }) return items
        val kept = items.filter { item -> entityOf(item)?.let { overlay[it]?.hidden } != true }
        return if (kept.size == items.size) items else kept
    }
}

/**
 * The overlay writes behind the long-press Hide and its Undo — explicit sets, never a toggle (the
 * guide used to toggle, so a hide that had already synced in would have un-hidden the channel).
 */
internal object IptvChannelQuickActions {
    fun hide(target: IptvChannelQuickActionsPolicy.HideTarget) =
        IptvOverlayRepository.setChannelHidden(target.entityId, target.playlistId, hidden = true)

    fun undoHide(target: IptvChannelQuickActionsPolicy.HideTarget) =
        IptvOverlayRepository.setChannelHidden(target.entityId, target.playlistId, hidden = false)
}
