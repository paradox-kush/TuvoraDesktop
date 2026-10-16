package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Desktop registers only the tuvora:// scheme with the OS (Info.plist, HKCU on Windows, xdg-mime on Linux) - never
 * nuvio:// - so a party invite handed to the desktop app arrives as tuvora://party/<code>.
 */
class TuvoraSchemePartyLinkTest {
    @Test
    fun `tuvora scheme opens a party invite`() {
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("tuvora://party/K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("TUVORA://PARTY/k7m-2qx"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("tuvora://party?code=K7M2QX"))
    }

    @Test
    fun `tuvora party links need a whole code`() {
        assertNull(parseAppDeepLink("tuvora://party"))
        assertNull(parseAppDeepLink("tuvora://party/K7M"))
        assertNull(parseAppDeepLink("tuvora://evil.example.com/party/K7M2QX"))
    }
}
