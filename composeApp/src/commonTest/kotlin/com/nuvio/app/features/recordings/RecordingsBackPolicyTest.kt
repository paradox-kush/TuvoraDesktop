package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.RecordingsBackAction
import com.nuvio.app.features.recordings.internal.RecordingsBackPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingsBackPolicyTest {
    @Test
    fun `back closes an open details sheet before it leaves the page`() {
        assertEquals(RecordingsBackAction.CLOSE_SHEET, RecordingsBackPolicy.actionFor(sheetOpen = true))
    }

    @Test
    fun `back with no sheet leaves the page`() {
        assertEquals(RecordingsBackAction.LEAVE_PAGE, RecordingsBackPolicy.actionFor(sheetOpen = false))
    }
}
