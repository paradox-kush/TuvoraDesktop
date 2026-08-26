package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppForegroundMonitorTest {

    @Test
    fun `foreground triggers foreground work`() {
        assertTrue(shouldTriggerForegroundWork(AppVisibility.Foreground))
    }

    @Test
    fun `background never triggers foreground work`() {
        // Regression (1.5.7 sync degradation): the Flow<Unit> -> Flow<AppVisibility> refactor let
        // Background emissions reach both events() consumers unfiltered. A pull kicked off as the
        // app backgrounds fails (network torn / token flap) and arms SyncManager's per-profile
        // retry-backoff, which then suppresses the NEXT, legitimate foreground pull
        // (requestForegroundPull's force flag bypasses the recency throttle but NOT isInRetryBackoff).
        // Background must never trigger foreground work.
        assertFalse(shouldTriggerForegroundWork(AppVisibility.Background))
    }

    @Test
    fun `foregroundEvents keeps only foreground emissions`() = runBlocking {
        val emitted = flowOf(
            AppVisibility.Foreground,
            AppVisibility.Background,
            AppVisibility.Foreground,
            AppVisibility.Background,
        ).foregroundEvents().toList()

        assertEquals(listOf(AppVisibility.Foreground, AppVisibility.Foreground), emitted)
    }
}
