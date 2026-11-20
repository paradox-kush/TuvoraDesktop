package com.nuvio.app.features.notifications

import com.nuvio.app.core.contracts.isEpisodicShowType
import com.nuvio.app.features.library.LibraryItem

/**
 * Which titles the alert store tracks. Only episodic shows (see [isEpisodicShowType]): the Library also holds live
 * channels (type `tv`) and movies, and neither has an episode release date. Enrolment, the test-notification picker
 * and the load-time migration all go through here.
 */
internal object TrackedShows {
    fun isTrackable(type: String, contentId: String): Boolean = isEpisodicShowType(type, contentId)

    /** Library items that can have episode alerts. */
    fun enrollable(items: List<LibraryItem>): List<LibraryItem> = items.filter { isTrackable(it.type, it.id) }

    /** Drops stored entries that are not episodic shows (e.g. live channels saved by older builds). */
    fun prune(shows: List<TrackedFollowedShow>): List<TrackedFollowedShow> =
        shows.filter { isTrackable(it.contentType, it.contentId) }

    /** What the Send-test notification is about: the first saved show. A channel or movie is never the pick. */
    fun testTarget(items: List<LibraryItem>): LibraryItem? = items.firstOrNull { isTrackable(it.type, it.id) }
}
