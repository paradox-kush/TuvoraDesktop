package com.nuvio.app.core.contracts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The in-app announcement card on the home screen. The shared home feed hosts it as one item but
 * must not import the announcements feature: the feature renders the card and owns its state, the
 * home screen only decides WHEN it is visible. No-op default: with nothing registered the home
 * screen renders no card and makes no request.
 */
interface HomeAnnouncementsSection {
    /**
     * Called each time the home screen becomes RESUMED. Fetches only when the refresh policy says
     * the cache is due; otherwise it makes no request at all. Never throws.
     */
    suspend fun refreshIfDue()

    @Composable
    fun Render(modifier: Modifier)
}

object HomeAnnouncementsSectionAccess {
    private var section: HomeAnnouncementsSection? = null

    fun register(s: HomeAnnouncementsSection) {
        section = s
    }

    /** Null until the announcements feature registers — the no-op default. */
    fun current(): HomeAnnouncementsSection? = section

    fun resetForTest() {
        section = null
    }
}
