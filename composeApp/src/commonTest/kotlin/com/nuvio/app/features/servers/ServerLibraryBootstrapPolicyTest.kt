package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerLibraryBootstrapPolicyTest {
    private val base = ServerConnection("c", "jellyfin", "Den", "https://h", "m", "u", "viewer", "k")

    @Test
    fun `an imported server with no library list refreshes once it can sign in`() {
        assertTrue(ServerLibraryBootstrapPolicy.needsRefresh(base, hasToken = true))
    }

    @Test
    fun `servers that already list libraries or cannot sign in or are disabled do not`() {
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base.copy(libraries = listOf(ServerLibrary("l", "Movies", ServerMediaKind.MOVIE))), true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base, hasToken = false))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base.copy(enabled = false), hasToken = true))
    }
}
