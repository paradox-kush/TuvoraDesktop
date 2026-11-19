package com.nuvio.app.features.iptv

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.iptv.PinSuggestionPolicy.Candidate
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock

/**
 * What "Pin" does. On Desktop (as on every Tuvora app) a pin is a LIVE FAVOURITE: a library item of type
 * "tv", which the hub's Live tab lists in the Favorites row above every category, in the synced favourites
 * order ([LiveFavouritesOrder]). A new favourite is stamped "now", so it lands first.
 */
fun interface LiveFavouritePort {
    fun addFavourite(contentId: String, name: String, logo: String?)
}

/** The real port: Tuvora's own (synced) library, even when Movies/Series come from Trakt or Simkl. */
internal object LibraryLiveFavouritePort : LiveFavouritePort {
    override fun addFavourite(contentId: String, name: String, logo: String?) {
        // save() = upsert, never a toggle: pinning a channel that is already a favourite cannot un-favourite it.
        LibraryRepository.save(
            LibraryItem(
                id = contentId,
                type = "tv",
                name = name,
                poster = logo,
                logo = logo,
                posterShape = PosterShape.Landscape,
                savedAtEpochMs = 0L, // stamped by LibraryRepository.save()
            ),
        )
    }
}

/**
 * The viewer's actions on the suggestion rail, with the store and the pin mechanism injected so "Pin all"
 * is tested with a fake port. The rail shows what [PinSuggestionPolicy] says; these only change state.
 */
class LivePinSuggestionActions(
    private val store: LivePinSuggestionStore,
    private val port: LiveFavouritePort,
) {
    /** Pins one channel. It stops qualifying because it is now a favourite. */
    fun pin(candidate: Candidate) = port.addFavourite(candidate.contentId, candidate.name, candidate.logo)

    /**
     * Pins every offered channel, least played first: each new favourite is stamped later than the last, so
     * the most played channel ends on top. Returns how many were pinned.
     */
    fun pinAll(candidates: List<Candidate>): Int {
        candidates.asReversed().forEach(::pin)
        return candidates.size
    }

    /** "Not now": quiet for 14 days. */
    fun notNow() = store.snooze()

    /** "Stop suggesting" / Settings switch off: never again until the viewer turns it back on. */
    fun stopSuggesting() = store.setEnabled(false)
}

/** The app's single store and actions, wired to the device prefs bag, the active profile and the wall clock. */
internal object LivePinSuggestions {
    val store = LivePinSuggestionStore(
        persistence = object : LivePinSuggestionPersistence {
            override fun load(profileId: Int): String? = XtreamAccountStorage.loadPinSuggestionsJson(profileId)
            override fun save(profileId: Int, json: String) = XtreamAccountStorage.savePinSuggestionsJson(profileId, json)
        },
        nowMs = { TraktPlatformClock.nowEpochMs() },
        profileId = { ProfileRepository.activeProfileId },
    )
    val actions = LivePinSuggestionActions(store, LibraryLiveFavouritePort)
}
