package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs [ServerFailoverGolden] (Step 0.3) — the table NuvioTV's JUnit copy must also pass. */
class ServerFailoverPolicyTest {

    @Test
    fun `golden policy table`() {
        assertEquals(ServerFailoverGolden.W, ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS, "window constant")
        for (case in ServerFailoverGolden.policyCases) {
            var state = case.initial
            case.steps.forEachIndexed { i, step ->
                val label = "${case.name} / step $i"
                val order = ServerFailoverPolicy.order(state, case.serverCount, step.nowMs)
                assertEquals(step.expectedOrder, order, "$label: order")
                val served = order.firstOrNull { it in step.up }
                assertEquals(step.expectedServed, served, "$label: served")
                state = if (served == null) ServerFailoverPolicy.onAllFailed(state)
                else ServerFailoverPolicy.onSuccess(state, served, step.nowMs, case.serverCount)
                assertEquals(step.expectedState, state, "$label: state")
            }
        }
    }

    @Test
    fun `golden failure classification`() {
        for ((kind, status, expected) in ServerFailoverGolden.classifierCases) {
            assertEquals(expected, FailoverFailureClassifier.shouldFailOver(FailoverFailure(kind, status)), "$kind $status")
        }
    }

    @Test
    fun `backup label names the backup by its index`() {
        assertEquals(null, ServerFailoverPolicy.backupLabel(0))
        assertEquals("Using backup server 1", ServerFailoverPolicy.backupLabel(1))
        assertEquals("Using backup server 3", ServerFailoverPolicy.backupLabel(3))
    }
}
