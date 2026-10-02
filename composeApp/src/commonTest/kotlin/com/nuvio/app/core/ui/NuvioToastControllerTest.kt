package com.nuvio.app.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UX35 + UX36: a toast can sit at the bottom and carry one action (Undo) that runs once. */
class NuvioToastControllerTest {

    @AfterTest
    fun clear() = NuvioToastController.dismiss()

    @Test
    fun a_toast_defaults_to_the_top_with_no_action() {
        NuvioToastController.show("Saved")
        val toast = NuvioToastController.currentToast.value!!
        assertEquals(NuvioToastPlacement.Top, toast.placement)
        assertNull(toast.actionLabel)
    }

    @Test
    fun the_action_runs_once_and_dismisses_the_toast() {
        var undone = 0
        NuvioToastController.show("Hidden", placement = NuvioToastPlacement.Bottom, actionLabel = "Undo", onAction = { undone++ })
        val toast = NuvioToastController.currentToast.value!!
        assertEquals(NuvioToastPlacement.Bottom, toast.placement)
        NuvioToastController.performAction(toast.id)
        NuvioToastController.performAction(toast.id)
        assertEquals(1, undone)
        assertNull(NuvioToastController.currentToast.value)
    }

    @Test
    fun the_action_of_a_replaced_toast_does_nothing() {
        var undone = 0
        NuvioToastController.show("Hidden", actionLabel = "Undo", onAction = { undone++ })
        val stale = NuvioToastController.currentToast.value!!.id
        NuvioToastController.show("Something else")
        NuvioToastController.performAction(stale)
        assertEquals(0, undone)
        assertEquals("Something else", NuvioToastController.currentToast.value?.message)
    }
}
