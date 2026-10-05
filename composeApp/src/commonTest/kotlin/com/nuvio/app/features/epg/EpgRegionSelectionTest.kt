package com.nuvio.app.features.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B119 (found on TV, same picker logic here): with "All" active (stored as the empty set) every
 * region row drew UNCHECKED, so one tap on a region turned "all 23" into "just this one" and Apply
 * dropped the other 22 regions' guide data. KMP twin of NuvioTV's EpgRegionSelectionTest
 * (kotlin.test order: expected, actual, message).
 */
class EpgRegionSelectionTest {

    private val all = listOf("United Kingdom", "India", "United States")

    @Test
    fun withAllActiveEveryRegionReadsAsChecked() {
        all.forEach { assertTrue(EpgRegionSelection.isChecked(emptySet(), it), "$it must read checked under All") }
    }

    @Test
    fun tappingARegionWhileAllIsActiveRemovesOnlyThatRegion() {
        assertEquals(setOf("United Kingdom", "United States"), EpgRegionSelection.toggle(emptySet(), all, "India"), "All minus India")
    }

    @Test
    fun togglingBackToEveryRegionCollapsesToAll() {
        assertEquals(emptySet(), EpgRegionSelection.toggle(setOf("United Kingdom", "United States"), all, "India"), "stored as All")
    }

    @Test
    fun aNarrowedSelectionStillTogglesNormally() {
        assertEquals(setOf("India", "United Kingdom"), EpgRegionSelection.toggle(setOf("India"), all, "United Kingdom"), "add")
        assertEquals(setOf("India"), EpgRegionSelection.toggle(setOf("India", "United Kingdom"), all, "United Kingdom"), "remove")
        assertFalse(EpgRegionSelection.isChecked(setOf("India"), "United Kingdom"), "unselected reads unchecked")
    }

    @Test
    fun theLastCheckedRegionCannotBeUnchecked() {
        assertEquals(setOf("India"), EpgRegionSelection.toggle(setOf("India"), all, "India"), "no-op")
    }

    @Test
    fun regionsThatVanishedFromTheCatalogDoNotBlockTheCollapseToAll() {
        assertEquals(emptySet(), EpgRegionSelection.toggle(setOf("United Kingdom", "United States", "Atlantis"), all, "India"), "stale name ignored")
    }
}
