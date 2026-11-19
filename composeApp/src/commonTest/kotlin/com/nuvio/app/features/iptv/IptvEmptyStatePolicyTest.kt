package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvEmptyStatePolicyTest {

    @Test
    fun `an empty section offers Retry before the viewer has retried`() {
        val d = IptvEmptyStatePolicy.decide(retried = false, status = LoadStatus.Empty)
        assertTrue(d.offersRetry, "empty must offer Retry")
        assertFalse(d.checkedJustNow, "nothing was re-checked yet")
    }

    @Test
    fun `a loaded but fully filtered list also offers Retry`() {
        val d = IptvEmptyStatePolicy.decide(retried = false, status = LoadStatus.Loaded)
        assertTrue(d.offersRetry)
        assertFalse(d.checkedJustNow)
    }

    @Test
    fun `empty again after a Retry says it was checked just now`() {
        assertTrue(IptvEmptyStatePolicy.decide(retried = true, status = LoadStatus.Empty).checkedJustNow)
        assertTrue(IptvEmptyStatePolicy.decide(retried = true, status = LoadStatus.Loaded).checkedJustNow)
        assertTrue(IptvEmptyStatePolicy.decide(retried = true, status = LoadStatus.Empty).offersRetry, "Retry stays available")
    }

    @Test
    fun `a disabled content type has nothing to retry and never claims a check`() {
        val d = IptvEmptyStatePolicy.decide(retried = true, status = LoadStatus.Idle)
        assertFalse(d.offersRetry)
        assertEquals(false, d.checkedJustNow)
    }

    @Test
    fun `a failed re-check is the failure card not a checked result`() {
        val d = IptvEmptyStatePolicy.decide(retried = true, status = LoadStatus.Failed(timedOut = false))
        assertFalse(d.checkedJustNow)
    }
}
