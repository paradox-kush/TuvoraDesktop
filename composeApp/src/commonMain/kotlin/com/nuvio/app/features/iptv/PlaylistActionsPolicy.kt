package com.nuvio.app.features.iptv

/** What a saved playlist's actions dialog offers, besides Enable/Disable (the dialog's confirm slot). */
internal enum class PlaylistAction { EDIT, CONTENT, HIDDEN, REMATCH, REMOVE }

/**
 * The playlist actions dialog's decisions, kept out of the Composable so they test without UI.
 *
 * B57 (GitHub #19): Remove used to be the dialog's dismiss button — the slot users read as Cancel —
 * styled like "Disable", and it deleted at once, syncing the delete to every device. It is now a
 * labelled row in the dialog body, last, destructive, and confirmed first with a message that names
 * the playlist and what goes with it ([XtreamRepository.remove] also drops the playlist's
 * favourites, watch progress and cached catalog).
 */
internal object PlaylistActionsPolicy {

    /** Rows in the dialog body, in order. Remove is offered for every source type and comes last. */
    fun bodyActions(account: XtreamAccount): List<PlaylistAction> = buildList {
        add(PlaylistAction.EDIT)
        add(PlaylistAction.CONTENT)
        // F02: hides made on a device (or the website) are undone here, on every source type.
        add(PlaylistAction.HIDDEN)
        // Only Xtream playlists build the TMDB match index whose negative verdicts a re-match resets.
        if (account.sourceType == SOURCE_TYPE_XTREAM) add(PlaylistAction.REMATCH)
        add(PlaylistAction.REMOVE)
    }

    fun label(action: PlaylistAction): String = when (action) {
        PlaylistAction.EDIT -> "Edit playlist"
        PlaylistAction.CONTENT -> "Content & Categories"
        PlaylistAction.HIDDEN -> "Hidden channels & groups"
        PlaylistAction.REMATCH -> "Re-match catalog"
        PlaylistAction.REMOVE -> "Remove playlist"
    }

    fun isDestructive(action: PlaylistAction): Boolean = action == PlaylistAction.REMOVE

    fun requiresConfirmation(action: PlaylistAction): Boolean = action == PlaylistAction.REMOVE

    fun removeConfirmTitle(account: XtreamAccount): String = "Remove playlist?"

    fun removeConfirmMessage(account: XtreamAccount): String =
        "“${account.name}” will be removed from this profile, along with its favourites, " +
            "watch progress and saved catalog. If you sync, it is removed from your other devices too."
}
