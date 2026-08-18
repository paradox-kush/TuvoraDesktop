package com.nuvio.app.features.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The region picker exists because the mirror index is mostly dead weight per household: on a
 * real 11k-channel panel only 2,035 of 15,397 indexed EPG channels ever matched. These pin the
 * rules that decide what a selection keeps — getting them wrong silently drops a viewer's EPG.
 */
class EpgRegionCatalogTest {

    private val sources = listOf(
        EpgSourceInfo("epgshare-uk1", "epgshare UK1", "United Kingdom", 481),
        EpgSourceInfo("epgshare-us2", "epgshare US2", "United States", 763),
        EpgSourceInfo("epgshare-in1", "epgshare IN1", "India", 1166),
        EpgSourceInfo("epgenius-31", "EPGenius GR/CY", "Cyprus,Greece", 400),
        EpgSourceInfo("mystery", "Unlabelled feed", null, 50),
    )

    @Test
    fun flagsComeFromCountryNames() {
        assertEquals("🇬🇧", EpgRegionCatalog.flagFor("United Kingdom"))
        assertEquals("🇮🇳", EpgRegionCatalog.flagFor("india"))
        // Unknown name degrades to no flag rather than a wrong one.
        assertEquals("", EpgRegionCatalog.flagFor("Atlantis"))
    }

    @Test
    fun aMultiCountrySourceAppearsUnderEachCountry() {
        val catalog = EpgRegionCatalog.catalogFrom(sources)
        val greece = catalog.first { it.name == "Greece" }
        val cyprus = catalog.first { it.name == "Cyprus" }
        assertTrue("epgenius-31" in greece.slugs)
        assertTrue("epgenius-31" in cyprus.slugs)
    }

    @Test
    fun catalogIsOrderedByCoverageSoTheUsefulRegionsLeadTheList() {
        val catalog = EpgRegionCatalog.catalogFrom(sources)
        assertEquals("India", catalog.first().name)
    }

    @Test
    fun anUnlabelledSourceBecomesTheOtherRegion() {
        val catalog = EpgRegionCatalog.catalogFrom(sources)
        val other = catalog.first { it.name == EpgRegionCatalog.UNCLASSIFIED }
        assertEquals(setOf("mystery"), other.slugs)
    }

    /** The picker is opt-in: an untouched install must behave exactly as it did before. */
    @Test
    fun anEmptySelectionKeepsEverything() {
        assertEquals(
            sources.map { it.slug }.toSet(),
            EpgRegionCatalog.slugsFor(emptySet(), sources),
        )
    }

    @Test
    fun aSelectionKeepsOnlyItsRegionsPlusUnclassified() {
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom", "India"), sources)
        assertTrue("epgshare-uk1" in kept)
        assertTrue("epgshare-in1" in kept)
        assertTrue("epgshare-us2" !in kept, "US was not selected")
        // Never hide a source the backend didn't label — that would drop coverage the viewer
        // never chose to drop.
        assertTrue("mystery" in kept, "unclassified sources always survive a filter")
    }

    @Test
    fun selectionMatchingIsCaseInsensitive() {
        val kept = EpgRegionCatalog.slugsFor(setOf("united kingdom"), sources)
        assertTrue("epgshare-uk1" in kept)
    }

    @Test
    fun selectingOneCountryOfAMultiCountrySourceKeepsIt() {
        // Dropping the shared feed would take Greece's EPG away with Cyprus's.
        val kept = EpgRegionCatalog.slugsFor(setOf("Greece"), sources)
        assertTrue("epgenius-31" in kept)
    }

    // --- Sports Centre must not be collateral damage of a GUIDE setting -----------------------
    //
    // Field-found 2026-08-18 by inspection: the picker was scoped to one job (shrink the guide
    // index) and silently acquired a second (choose which feeds Sports Centre can match against).
    // `epgshare-us-sports1` is published with countries "United States", so a viewer who picked
    // "United Kingdom" for their guide was also deleting the feed the sports matcher runs on —
    // with nothing on screen connecting the two.

    private val sportsSources = sources + listOf(
        EpgSourceInfo("epgshare-us-sports1", "epgshare US sports", "United States", 120),
        EpgSourceInfo("epgenius-14", "EPGenius someone/B1G", "United States", 60),
    )

    @Test
    fun aGuideRegionSelectionKeepsTheSportsFeeds() {
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue(
            "epgshare-us-sports1" in kept,
            "a sports feed backs Sports Centre, not the guide — a guide region must never delete it",
        )
    }

    @Test
    fun aGuideRegionSelectionKeepsTheCuratedEpgeniusFeeds() {
        // The backend allowlists 7 EPGenius ids by hand for COVERAGE, not by region; they are the
        // sports backbone. Region-filtering a curated allowlist throws away the curation.
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue("epgenius-14" in kept, "curated EPGenius feeds survive any region selection")
    }

    @Test
    fun keepingSportsDoesNotReadmitTheGeneralFeedsTheViewerFilteredOut() {
        // The whole point of the picker still has to work: a plain US guide dump stays dropped.
        val kept = EpgRegionCatalog.slugsFor(setOf("United Kingdom"), sportsSources)
        assertTrue("epgshare-us2" !in kept, "a general US guide feed is still filtered out")
    }

    @Test
    fun regionsImpliedByFollowedLeaguesSurviveTheSelection() {
        // A viewer following Liga MX needs Mexico's feed even though their guide is UK-only.
        val withMx = sportsSources + EpgSourceInfo("epgshare-mx1", "epgshare MX1", "Mexico", 200)
        val kept = EpgRegionCatalog.slugsFor(
            selection = setOf("United Kingdom"),
            sources = withMx,
            alsoKeepRegions = setOf("Mexico"),
        )
        assertTrue("epgshare-mx1" in kept, "a followed league's country is not optional coverage")
        assertTrue("epgshare-us2" !in kept, "and nothing else leaks back in")
    }
}
