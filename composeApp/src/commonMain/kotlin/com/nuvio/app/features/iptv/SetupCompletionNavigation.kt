package com.nuvio.app.features.iptv

/** Where a finished redeem leaves the person: pages to leave, then the playlist details to open (if any). */
internal data class SetupCompletionPlan(val pops: Int, val openDetailsKey: String?)

/**
 * Every successful redeem ends on the new playlist's details. When it cannot (the playlist belongs to a profile
 * that is not the one loaded here, so its details have no account to show) it ends on the IPTV list, whose toast
 * names the profile. [fromAddPage]: the preview was opened from the Add Playlist page (two pages on the stack to
 * leave); from a link or after sign-in it stands alone (one).
 */
internal object SetupCompletionNavigation {
    fun plan(completion: SetupCompletion, fromAddPage: Boolean): SetupCompletionPlan =
        SetupCompletionPlan(pops = if (fromAddPage) 2 else 1, openDetailsKey = completion.openPlaylistKey)
}

/** How the preview page was reached, set by whoever opens it (a plain flag: pages are not given arguments). */
internal object SetupPreviewEntry {
    var fromAddPage: Boolean = false
}
