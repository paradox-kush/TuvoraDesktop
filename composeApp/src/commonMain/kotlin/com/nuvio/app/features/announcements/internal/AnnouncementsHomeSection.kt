package com.nuvio.app.features.announcements.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.rememberSafeUriOpener
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.contracts.HomeAnnouncementsSection
import com.nuvio.app.features.announcements.api.Announcements
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Home-screen adapter: renders the port's current announcement; no I/O in the UI. */
internal class AnnouncementsHomeSection(
    private val announcements: Announcements,
) : HomeAnnouncementsSection {

    /** Runs for as long as Home is RESUMED: one due-check fetch, plus following sign-in changes. */
    override suspend fun refreshIfDue() {
        coroutineScope {
            launch { announcements.followSignIn() }
            announcements.refreshIfDue()
        }
    }

    @Composable
    override fun hasContent(): Boolean =
        announcements.visible.collectAsStateWithLifecycle().value != null

    @Composable
    override fun Render(modifier: Modifier) {
        val current by announcements.visible.collectAsStateWithLifecycle()
        val announcement = current ?: return
        val openUri = rememberSafeUriOpener()
        AnnouncementCard(
            announcement = announcement,
            onCtaClick = { url -> openUri(url) },
            onDismiss = { announcements.dismiss(announcement.id) },
            modifier = modifier,
        )
    }
}
