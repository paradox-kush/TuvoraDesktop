package com.nuvio.app.features.details

import com.nuvio.app.features.tmdb.TmdbSettings

/** What pressing a cast member does (UX70). */
internal sealed interface CastClickAction {
    /** Open the person's TMDB page. */
    data class OpenPerson(val tmdbId: Int) : CastClickAction

    /** The person has no TMDB id because TMDB people are switched off — say so instead of doing nothing. */
    data object ExplainTmdbOff : CastClickAction
}

/**
 * UX70: with TMDB enrichment (or its credits) off, cast comes from the addon without TMDB ids, so a
 * press used to do nothing and say nothing. Pure.
 */
internal object CastClickPolicy {

    /** Cast carries TMDB ids only when enrichment AND its credits source are on. */
    fun peopleEnabled(settings: TmdbSettings): Boolean = settings.enabled && settings.useCredits

    /**
     * `null` = not pressable: TMDB people are on but could not identify this person, so the hint
     * ("turn it on") would be wrong.
     */
    fun actionFor(tmdbId: Int?, tmdbPeopleEnabled: Boolean): CastClickAction? = when {
        tmdbId != null && tmdbId > 0 -> CastClickAction.OpenPerson(tmdbId)
        !tmdbPeopleEnabled -> CastClickAction.ExplainTmdbOff
        else -> null
    }
}
