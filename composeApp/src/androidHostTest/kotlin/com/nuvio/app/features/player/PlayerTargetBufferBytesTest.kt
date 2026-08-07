package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTargetBufferBytesTest {

    private val mb = 1024L * 1024L

    @Test
    fun lowRamDevicesGetTheFloorRegardlessOfHeap() {
        assertEquals(24 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = true, maxHeapBytes = 512 * mb))
    }

    @Test
    fun standardPhoneHeapBudgetsAQuarter() {
        // The common case in the field: 256MB growth limit, where the old flat 100MB target
        // left the rest of the app ~150MB and OOMed flagships mid-stream.
        assertEquals(64 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = false, maxHeapBytes = 256 * mb))
        assertEquals(48 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = false, maxHeapBytes = 192 * mb))
        assertEquals(32 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = false, maxHeapBytes = 128 * mb))
    }

    @Test
    fun tinyHeapsHitTheFloorAndHugeHeapsTheCeiling() {
        assertEquals(24 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = false, maxHeapBytes = 64 * mb))
        assertEquals(64 * mb.toInt(), playerTargetBufferBytes(isLowRamDevice = false, maxHeapBytes = 1024 * mb))
    }
}
