package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedMpvInstanceGateTest {
    @Test
    fun replacementWaitsUntilPredecessorCompletesDestruction() {
        val gate = SerializedMpvInstanceGate()
        val first = gate.register()
        val second = gate.register()
        var firstReady = false
        var secondReady = false
        var activeWhenSecondBecameReady = -1

        first.whenReady { firstReady = true }
        second.whenReady {
            secondReady = true
            activeWhenSecondBecameReady = gate.snapshot().initializedInstances
        }

        assertTrue(firstReady)
        assertFalse(secondReady)
        first.markInitialized()
        assertEquals(1, gate.snapshot().initializedInstances)

        first.complete()

        assertTrue(secondReady)
        assertEquals(0, activeWhenSecondBecameReady)
        assertEquals(0, gate.snapshot().initializedInstances)
        second.complete()
    }

    @Test
    fun completionIsIdempotentAndUnblocksEveryLaterLeaseInOrder() {
        val gate = SerializedMpvInstanceGate()
        val order = mutableListOf<Long>()
        val first = gate.register()
        val second = gate.register()
        val third = gate.register()
        first.whenReady { order += first.id }
        second.whenReady { order += second.id }
        third.whenReady { order += third.id }

        first.complete()
        first.complete()
        second.complete()
        third.complete()

        assertEquals(listOf(first.id, second.id, third.id), order)
        assertEquals(0, gate.snapshot().waitingInstances)
    }
}
