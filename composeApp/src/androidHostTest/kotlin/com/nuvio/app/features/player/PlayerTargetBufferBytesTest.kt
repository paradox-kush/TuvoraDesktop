package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.MemoryTier
import com.nuvio.app.core.contracts.MemoryTierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTargetBufferBytesTest {

    private val mb = 1024L * 1024L

    @Test
    fun lowTierDevicesGetTheFloorRegardlessOfHeap() {
        assertEquals(24 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.LOW, maxHeapBytes = 512 * mb))
    }

    @Test
    fun standardPhoneHeapBudgetsAQuarter() {
        // The common case in the field: 256MB growth limit, where the old flat 100MB target
        // left the rest of the app ~150MB and OOMed flagships mid-stream.
        assertEquals(64 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.HIGH, maxHeapBytes = 256 * mb))
        assertEquals(48 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.HIGH, maxHeapBytes = 192 * mb))
        assertEquals(32 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.HIGH, maxHeapBytes = 128 * mb))
    }

    @Test
    fun midTierUsesTheSameQuarterFormulaAsHigh() {
        // Only LOW gets a special size; MID and HIGH share the heap/4 budget.
        assertEquals(64 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.MID, maxHeapBytes = 256 * mb))
    }

    @Test
    fun tinyHeapsHitTheFloorAndHugeHeapsTheCeiling() {
        assertEquals(24 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.HIGH, maxHeapBytes = 64 * mb))
        assertEquals(64 * mb.toInt(), playerTargetBufferBytes(tier = MemoryTier.HIGH, maxHeapBytes = 1024 * mb))
    }

    @Test
    fun aLowMemoryClassDeviceWithoutTheFlagGetsTheLowBuffer() {
        // The deliberate Fix 4 behavior change: a 192MB-memory-class device that never sets
        // isLowRamDevice used to take the heap/4 path; the MemoryTier selector now routes it
        // to the LOW floor, same as flagged low-RAM hardware.
        val tier = MemoryTierPolicy.androidTier(isLowRamDevice = false, memoryClassMb = 192)
        assertEquals(MemoryTier.LOW, tier)
        assertEquals(24 * mb.toInt(), playerTargetBufferBytes(tier = tier, maxHeapBytes = 512 * mb))
    }
}
