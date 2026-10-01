package com.nuvio.app.features.announcements.api

import com.nuvio.app.core.contracts.HomeAnnouncementsSection
import com.nuvio.app.features.announcements.internal.AnnouncementsHomeSection
import com.nuvio.app.features.announcements.internal.AnnouncementsRepository
import kotlinx.coroutines.flow.StateFlow

/** The announcements port: what to show now, when to refresh, and how to dismiss. */
interface Announcements {
    /** The single announcement to show (server order, not dismissed, meant for this viewer), or null. */
    val visible: StateFlow<Announcement?>

    /** Fetch only when the refresh policy says the cache is due. Failures keep the cache. */
    suspend fun refreshIfDue()

    /**
     * Keeps [visible] in step with who is signed in (a policy notice is only for people who used
     * the app under the older terms). Suspends until cancelled: call it only while the surface is
     * visible. Reads local auth state only and never touches the network.
     */
    suspend fun followSignIn()

    /** Hide [id] immediately and remember it on this device. */
    fun dismiss(id: String)
}

/**
 * Api-level provider factory (rules doc Rule 1 point 1) — the only sanctioned way the wiring file
 * obtains the implementation. Returns STABLE instances: built once per process, lazily, so nothing
 * touches platform storage before the platform has initialised it.
 */
object AnnouncementsFeature {
    private val port: Announcements by lazy { AnnouncementsRepository.platformDefault() }
    private val home: HomeAnnouncementsSection by lazy { AnnouncementsHomeSection(port) }

    fun provide(): Announcements = port

    fun homeSection(): HomeAnnouncementsSection = home
}
