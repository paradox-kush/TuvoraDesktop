package com.nuvio.app.core.ui

/**
 * Where a keyed lazy list should sit after its items change, when an item comes BACK into it.
 *
 * Lazy lists keep the first visible item in view by KEY. That is right for a page appended or a row
 * removed, but wrong for an item restored at the leading edge: hide the first card of a row and the
 * next card becomes the first visible one; Undo the hide and the restored card is inserted ahead of
 * that anchor, so the list keeps the anchor in view and the restored card lands one slot off-screen
 * (K9 — "Undo doesn't bring the channel back" in the IPTV hub, the View-all grid and the guide).
 *
 * Pure, so it tests without Compose; [KeepRestoredItemsInView] applies it to a list state.
 */
object RestoredItemAnchorPolicy {

    /**
     * The index to pin the list's first visible slot to, or null to let the list keep its own
     * key-based anchor. [anchorKey] is the key of the item the list showed first (from its last
     * layout — a key, so it reads the same whether or not the list has measured [keys] yet).
     *
     * Non-null only when the item now sitting in the anchor's old slot is new to the list (restored,
     * or inserted exactly there) and pushed the anchor further along: an item restored somewhere
     * off-screen never yanks a scrolled list, and appends, removals and replaced lists never move it.
     */
    fun restoredSlot(previousKeys: List<Any>, keys: List<Any>, anchorKey: Any?): Int? {
        if (anchorKey == null || previousKeys.isEmpty()) return null
        val oldIndex = previousKeys.indexOf(anchorKey)
        if (oldIndex < 0 || keys.indexOf(anchorKey) <= oldIndex) return null
        val atSlot = keys.getOrNull(oldIndex) ?: return null
        return if (atSlot in previousKeys.toHashSet()) null else oldIndex
    }
}
