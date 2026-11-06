package com.nuvio.app.features.livetv.pause

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB

class LivePauseDesktopBudgetTest {
    @Test
    fun `the normal plan is what the desktop bridges ship`() {
        val plan = LivePauseDeviceBudget.configForDesktop(16 * GIB, null).engineNormalPlan
        assertEquals(150 * MIB, plan.maxBytes, "forward cache")
        assertEquals(50 * MIB, plan.maxBackBytes, "back buffer")
    }

    @Test
    fun `a small or unknown machine never raises the caches`() {
        for (physical in listOf(0L, 2 * GIB, 4 * GIB - 1)) {
            assertEquals(0L, LivePauseDeviceBudget.configForDesktop(physical, null).engineMemoryBudgetBytes, "$physical bytes")
        }
    }

    @Test
    fun `the budget grows with installed memory`() {
        assertEquals(256 * MIB, LivePauseDeviceBudget.configForDesktop(4 * GIB, null).engineMemoryBudgetBytes)
        assertEquals(256 * MIB, LivePauseDeviceBudget.configForDesktop(6 * GIB, null).engineMemoryBudgetBytes)
        assertEquals(384 * MIB, LivePauseDeviceBudget.configForDesktop(8 * GIB, null).engineMemoryBudgetBytes)
        assertEquals(512 * MIB, LivePauseDeviceBudget.configForDesktop(16 * GIB, null).engineMemoryBudgetBytes)
        assertEquals(512 * MIB, LivePauseDeviceBudget.configForDesktop(128 * GIB, null).engineMemoryBudgetBytes)
    }

    @Test
    fun `a granted budget always covers the normal plan so raising never shrinks it`() {
        for (physical in listOf(4 * GIB, 8 * GIB, 16 * GIB, 64 * GIB)) {
            val c = LivePauseDeviceBudget.configForDesktop(physical, null)
            assertTrue(c.engineMemoryBudgetBytes >= c.engineNormalPlan.maxBytes + c.engineNormalPlan.maxBackBytes, "$physical")
        }
    }

    @Test
    fun `desktop has no disk buffer tier flag and keeps the chosen live buffer as read ahead`() {
        assertEquals(false, LivePauseDeviceBudget.configForDesktop(16 * GIB, null).lowMemoryTier)
        assertEquals(30L, LivePauseDeviceBudget.configForDesktop(16 * GIB, 30).engineNormalPlan.cacheSecs)
        assertEquals(LivePauseDeviceBudget.UNBOUNDED_CACHE_SECS, LivePauseDeviceBudget.configForDesktop(16 * GIB, 0).engineNormalPlan.cacheSecs)
    }
}
