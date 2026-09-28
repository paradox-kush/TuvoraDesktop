package com.nuvio.app.features.announcements.api

import com.nuvio.app.core.contracts.HomeAnnouncementsSection
import com.nuvio.app.features.announcements.internal.AnnouncementsHomeSection
import com.nuvio.app.features.announcements.internal.AnnouncementsRepository
import kotlinx.coroutines.flow.StateFlow

/** The announcements port: what to show now, when to refresh, and how to dismiss. */
interface Announcements {
    /** The single announcement to show (server order, not dismissed), or null. */
    val visible: StateFlow<Announcement?>

    /** Fetch only when the refresh policy says the cache is due. Failures keep the cache. */
    suspend fun refreshIfDue()

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
