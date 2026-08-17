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
}
