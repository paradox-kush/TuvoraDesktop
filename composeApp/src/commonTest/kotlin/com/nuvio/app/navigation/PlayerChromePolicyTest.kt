package com.nuvio.app.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerChromePolicyTest {
    @Test
    fun `only the player route gets landscape and hidden system bars`() {
        // Regression (2026-09-27): upstream moved the landscape lock + bar hiding out of the player
        // screen into its App shell; the fork keeps its own composition root, so after the merge
        // the phone player opened in portrait until App.kt applied the same route rule.
        assertTrue(wantsPlayerChrome(PlayerRoute(launchId = 1L)))
        assertFalse(wantsPlayerChrome(TabsRoute))
        assertFalse(wantsPlayerChrome(DetailRoute("movie", "tt1")))
        assertFalse(wantsPlayerChrome(null))
    }
}
