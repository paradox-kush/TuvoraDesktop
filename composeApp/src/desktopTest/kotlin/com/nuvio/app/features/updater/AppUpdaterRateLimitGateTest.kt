package com.nuvio.app.features.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B175: the in-memory rate-limit gate of the shared updater controller, as Desktop runs it. */
class AppUpdaterRateLimitGateTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a rate limited check blocks every later check until the reset`() {
        var now = 10_000L
        var requests = 0
        val controller = AppUpdaterController(scope, UpdatePreferences(), { _ ->
            requests += 1
            Result.failure(UpdaterRateLimitedException(now + 1_800L, "limited"))
        }, nowEpochSeconds = { now })

        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        controller.checkForUpdates(force = false, showNoUpdateFeedback = false)
        assertEquals(1, requests, "automatic re-check makes no request while limited")
        assertFalse(controller.uiState.value.showDialog, "automatic checks stay silent")

        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertEquals(1, requests, "a manual check makes no request either")
        assertTrue(controller.uiState.value.showDialog, "but it explains why")

        now += 1_800L
        controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
        assertEquals(2, requests, "after the reset the network is asked again")
    }
}
