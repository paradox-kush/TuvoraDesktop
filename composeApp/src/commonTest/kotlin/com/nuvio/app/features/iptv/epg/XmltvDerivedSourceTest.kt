package com.nuvio.app.features.iptv.epg

import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The derived Xtream guide URL — the one line that turns the whole-guide lane on for Xtream.
 *
 * Pinned by a test rather than by a live panel because getting it wrong is silent: a malformed URL
 * 404s once, the ladder falls through to the per-channel ask, and the app looks exactly as it did
 * before while paying a request per channel forever.
 */
class XmltvDerivedSourceTest {

    private fun acct(
        base: String = "http://panel.example:8080",
        user: String = "u",
        pass: String = "p",
        type: String = SOURCE_TYPE_XTREAM,
    ) = XtreamAccount(id = "a", name = "a", baseUrl = base, username = user, password = pass, sourceType = type)

    @Test
    fun `an xtream account derives its own xmltv url`() {
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=u&password=p",
            XmltvClient.derivedXmltvUrl(acct()),
        )
    }

    @Test
    fun `a trailing slash does not double up`() {
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=u&password=p",
            XmltvClient.derivedXmltvUrl(acct(base = "http://panel.example:8080/")),
        )
    }

    @Test
    fun `credentials with query-breaking characters are encoded`() {
        // Panels really do issue these; an unencoded & truncates the password server-side.
        val url = XmltvClient.derivedXmltvUrl(acct(user = "a b", pass = "p&q+r"))
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=a%20b&password=p%26q%2Br",
            url,
        )
    }

    @Test
    fun `non-xtream sources derive nothing`() {
        // M3U keeps its url-tvg header and Stalker has no such route; deriving one would 404 on
        // every refresh.
        assertNull(XmltvClient.derivedXmltvUrl(acct(type = "m3u_url")))
        assertNull(XmltvClient.derivedXmltvUrl(acct(type = "stalker")))
    }

    @Test
    fun `an account missing credentials derives nothing`() {
        assertNull(XmltvClient.derivedXmltvUrl(acct(user = "")))
        assertNull(XmltvClient.derivedXmltvUrl(acct(pass = "")))
        assertNull(XmltvClient.derivedXmltvUrl(acct(base = "")))
    }
}
