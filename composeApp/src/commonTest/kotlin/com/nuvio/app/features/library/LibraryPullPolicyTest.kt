package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertTrue

/** B03/D5 — favourites made on another device must arrive even when Trakt/Simkl sources the library. */
class LibraryPullPolicyTest {
    @Test
    fun theNuvioLibraryIsPulledUnderATrackingProvider() {
        assertTrue(LibraryPullPolicy.pullsNuvioLibrary(trackingProviderActive = true))
        assertTrue(LibraryPullPolicy.pullsNuvioLibrary(trackingProviderActive = false))
    }
}
