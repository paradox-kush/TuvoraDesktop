package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trakt is unsupported in builds without Trakt credentials. The Trakt-integration search entries
 * (connect-card page entry + its sub-setting rows) must hide when credentials are absent, while
 * Simkl and the unrelated Trakt-named rows (licenses attribution, MDBList rating provider) stay.
 */
class TraktSearchVisibilityTest {

    @Test
    fun traktIntegrationRowsHideWhenCredentialsAbsent() {
        for (key in traktIntegrationSearchKeys) {
            assertFalse(
                isTraktIntegrationEntryVisible(key, traktCredentialsConfigured = false),
                "Trakt-integration row '$key' must hide without credentials",
            )
            assertTrue(
                isTraktIntegrationEntryVisible(key, traktCredentialsConfigured = true),
                "Trakt-integration row '$key' must show when credentials are present",
            )
        }
    }

    @Test
    fun simklAndUnrelatedRowsAlwaysShow() {
        // Non-integration entries are visible regardless of Trakt credential state.
        for (key in listOf("simkl-authentication", "trakt-attribution", "mdb-trakt", "account")) {
            assertTrue(
                isTraktIntegrationEntryVisible(key, traktCredentialsConfigured = false),
                "'$key' must stay visible even without Trakt credentials",
            )
            assertTrue(
                isTraktIntegrationEntryVisible(key, traktCredentialsConfigured = true),
                "'$key' must stay visible with Trakt credentials",
            )
        }
    }

    @Test
    fun licensesAndRatingRowsAreNotTreatedAsTraktIntegration() {
        assertFalse("trakt-attribution" in traktIntegrationSearchKeys)
        assertFalse("mdb-trakt" in traktIntegrationSearchKeys)
    }
}
