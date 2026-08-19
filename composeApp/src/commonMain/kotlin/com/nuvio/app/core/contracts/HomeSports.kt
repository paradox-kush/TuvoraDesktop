package com.nuvio.app.core.contracts

import androidx.compose.runtime.Composable
import com.nuvio.app.features.home.MetaPreview

/**
 * The Sports Centre's presence on the home screen — a featured-event rail plus a set-up promo. The
 * shared home feed hosts it as one item but must not import the radar subsystem: the fork renders
 * the section and owns its state, receiving only neutral navigation callbacks. No-op default: with
 * nothing registered the home screen simply renders no sports section.
 */
interface HomeSportsSection {
    /** Warm the section's data (called from the home screen's load effect). */
    fun ensureLoaded()

    @Composable
    fun Render(
        onOpenSportsTab: () -> Unit,
        onPlayChannel: (String) -> Unit,
        onAddPlaylist: () -> Unit,
        onOpenRecordingPoster: (MetaPreview) -> Unit,
        onPlayReplay: (SportsReplay) -> Unit,
    )
}

object HomeSportsSectionAccess {
    private var section: HomeSportsSection? = null

    fun register(s: HomeSportsSection) {
        section = s
    }

    /** Null until the Sports feature registers — the no-op default. */
    fun current(): HomeSportsSection? = section

    fun resetForTest() {
        section = null
    }
}
