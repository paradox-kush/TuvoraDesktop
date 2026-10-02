package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Security M2: an Android activity that is recreated must not replay the link that started it. */
class IncomingLinkPolicyTest {
    private val link = "https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"

    @Test
    fun `a fresh launch handles the link in its intent`() {
        assertEquals(link, IncomingLinkPolicy.linkToHandle(restoredFromSavedState = false, dataString = link))
        assertEquals(link, IncomingLinkPolicy.linkToHandle(restoredFromSavedState = false, dataString = "  $link  "))
    }

    @Test
    fun `a recreated activity never replays the link`() {
        assertNull(IncomingLinkPolicy.linkToHandle(restoredFromSavedState = true, dataString = link),
            "locale / dark-mode / font-scale change, or process restore from Recents")
    }

    @Test
    fun `a blank or missing link is nothing to handle`() {
        assertNull(IncomingLinkPolicy.linkToHandle(restoredFromSavedState = false, dataString = null))
        assertNull(IncomingLinkPolicy.linkToHandle(restoredFromSavedState = false, dataString = "   "))
    }

    @Test
    fun `a new intent delivered to a running activity is always handled`() {
        assertEquals(link, IncomingLinkPolicy.linkFromNewIntent(link))
        assertNull(IncomingLinkPolicy.linkFromNewIntent(null))
    }
}
