package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonLoadRetryPolicyTest {
    private fun addon(
        url: String,
        loaded: Boolean = false,
        error: String? = null,
        refreshing: Boolean = false,
        enabled: Boolean = true,
    ) = ManagedAddon(
        manifestUrl = url,
        manifest = if (loaded) AddonManifest(id = url, name = url, description = "", version = "1", resources = emptyList(), types = emptyList(), transportUrl = url) else null,
        isRefreshing = refreshing,
        errorMessage = error,
        enabled = enabled,
    )

    @Test
    fun `the automatic ladder is short and then stops`() {
        assertEquals(2_000L, AddonLoadRetryPolicy.delayBeforeRetry(0))
        assertEquals(8_000L, AddonLoadRetryPolicy.delayBeforeRetry(1))
        assertNull(AddonLoadRetryPolicy.delayBeforeRetry(2))
    }

    @Test
    fun `only rows that failed and are still on Home are fetched again`() {
        val retry = AddonLoadRetryPolicy.catalogsToRetry(
            requestedCacheKeys = listOf("a", "b", "c"),
            failedCacheKeys = setOf("b", "gone"),
        )
        assertEquals(listOf("b"), retry)
    }

    @Test
    fun `nothing failed means no request at all`() {
        assertTrue(AddonLoadRetryPolicy.catalogsToRetry(listOf("a", "b"), emptySet()).isEmpty())
        assertTrue(AddonLoadRetryPolicy.manifestsToRetry(listOf(addon("x", loaded = true))).isEmpty())
    }

    @Test
    fun `only enabled add-ons whose manifest failed and is not in flight are fetched again`() {
        val retry = AddonLoadRetryPolicy.manifestsToRetry(
            listOf(
                addon("failed", error = "timeout"),
                addon("loaded", loaded = true),
                addon("in-flight", error = "timeout", refreshing = true),
                addon("disabled", error = "timeout", enabled = false),
                addon("pending"),
                addon("failed", error = "timeout"),
            ),
        )
        assertEquals(listOf("failed"), retry)
    }

    @Test
    fun `missing rows are said out loud once loading settles`() {
        assertTrue(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 1, failedManifestCount = 0, isLoading = false))
        assertTrue(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 0, failedManifestCount = 1, isLoading = false))
        assertFalse(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 1, failedManifestCount = 0, isLoading = true))
        assertFalse(AddonLoadRetryPolicy.showsPartialFailure(failedRowCount = 0, failedManifestCount = 0, isLoading = false))
    }
}
