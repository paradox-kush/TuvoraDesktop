package com.nuvio.app

import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.features.party.partyInviteLink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every app link Desktop generates must use the scheme the desktop installers register with the OS
 * (DesktopUrlSchemeRegistration: Info.plist on macOS, HKCU on Windows, xdg-mime on Linux). nuvio:// is not registered
 * on desktop, so a nuvio:// link the app hands out (a notification, a custom-server invite) could never come back.
 */
class DesktopGeneratedLinksTest {
    private fun schemeOf(link: String) = link.substringBefore("://")

    @Test
    fun `generated app links use the scheme desktop registers`() {
        val registered = DesktopUrlSchemeRegistration.SCHEME
        assertEquals(registered, schemeOf(buildMetaDeepLinkUrl("movie", "tt1")))
        assertEquals(registered, schemeOf(buildDownloadsDeepLinkUrl()))
        assertEquals(registered, schemeOf(partyInviteLink("K7M2QX", isCustomServer = true)))
    }
}
