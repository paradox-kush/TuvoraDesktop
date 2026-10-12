package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IptvCatalogFreshnessTest {
    private val hour = 3_600_000L
    private val built = 100 * hour
    @Test fun refreshOnlyStaleKindsAndDoNothingWhenIdle() {
        val stamps = mapOf("live" to built, "movies" to (built + 5 * hour))
        assertTrue(IptvCatalogFreshness.dueKinds(stamps, 6, built + 6 * hour) == listOf("live"))
        assertTrue(IptvCatalogFreshness.dueKinds(stamps, 6, built + 5 * hour).isEmpty())
    }
    @Test fun sixHourPlaylistDoesNotWaitThreeDays() {
        assertTrue(IptvCatalogFreshness.stale(built, 6, built + 6 * hour))
    }
    @Test fun freshPlaylistMakesNoRefreshRequest() {
        assertFalse(IptvCatalogFreshness.stale(built, 6, built + 6 * hour - 1))
    }
    @Test fun dailyPlaylistKeepsItsOwnCadence() {
        assertFalse(IptvCatalogFreshness.stale(built, 24, built + 6 * hour))
        assertTrue(IptvCatalogFreshness.stale(built, 24, built + 24 * hour))
    }
    @Test fun offRetainsExistingBrowseFallback() {
        assertFalse(IptvCatalogFreshness.stale(built, 0, built + 71 * hour))
        assertTrue(IptvCatalogFreshness.stale(built, 0, built + 72 * hour))
    }
    @Test fun stalkerSixHourSettingOverridesTwelveHourFallback() {
        assertTrue(IptvCatalogFreshness.stale(built, 6, built + 6 * hour, fallbackHours = 12))
        assertFalse(IptvCatalogFreshness.stale(built, 0, built + 6 * hour, fallbackHours = 12))
    }
    @Test fun missingAndFutureTimestampsSelfHeal() {
        assertTrue(IptvCatalogFreshness.stale(null, 6, built))
        assertTrue(IptvCatalogFreshness.stale(0, 6, built))
        assertTrue(IptvCatalogFreshness.stale(built, 6, built - 1))
    }
}
