package com.nuvio.app.features.details

import com.nuvio.app.features.tmdb.TmdbSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX70: pressing a cast member must never silently do nothing when TMDB people are switched off. */
class CastClickPolicyTest {

    @Test
    fun a_person_with_a_tmdb_id_opens_their_page() {
        assertEquals(CastClickAction.OpenPerson(17419), CastClickPolicy.actionFor(17419, tmdbPeopleEnabled = true))
        assertEquals(
            CastClickAction.OpenPerson(17419),
            CastClickPolicy.actionFor(17419, tmdbPeopleEnabled = false),
            "an id the addon already supplied still opens the page",
        )
    }

    @Test
    fun without_tmdb_people_a_press_explains_how_to_turn_them_on() {
        assertEquals(CastClickAction.ExplainTmdbOff, CastClickPolicy.actionFor(null, tmdbPeopleEnabled = false))
        assertEquals(CastClickAction.ExplainTmdbOff, CastClickPolicy.actionFor(0, tmdbPeopleEnabled = false))
    }

    @Test
    fun with_tmdb_on_a_person_tmdb_could_not_identify_stays_inert() {
        assertNull(
            CastClickPolicy.actionFor(null, tmdbPeopleEnabled = true),
            "telling the viewer to turn on what is already on would be wrong",
        )
    }

    @Test
    fun people_need_both_enrichment_and_credits() {
        assertFalse(CastClickPolicy.peopleEnabled(TmdbSettings()), "enrichment is off by default")
        assertTrue(CastClickPolicy.peopleEnabled(TmdbSettings(enabled = true, useCredits = true)))
        assertFalse(CastClickPolicy.peopleEnabled(TmdbSettings(enabled = true, useCredits = false)))
        assertFalse(CastClickPolicy.peopleEnabled(TmdbSettings(enabled = false, useCredits = true)))
    }
}
