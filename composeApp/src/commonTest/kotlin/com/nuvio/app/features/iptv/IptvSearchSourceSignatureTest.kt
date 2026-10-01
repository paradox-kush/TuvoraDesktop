package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.overlay.CategoryOverlay
import com.nuvio.app.features.iptv.overlay.ChannelOverlay
import com.nuvio.app.features.iptv.overlay.OverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** UX15: the fingerprint changes exactly when what IPTV search can return changes. */
class IptvSearchSourceSignatureTest {

    private val a = XtreamAccount(id = "http://a.test|u", name = "A", baseUrl = "http://a.test", username = "u", password = "p")
    private val b = XtreamAccount(id = "http://b.test|u", name = "B", baseUrl = "http://b.test", username = "u", password = "p")

    private fun sig(vararg accounts: XtreamAccount, overlay: OverlaySnapshot = OverlaySnapshot.EMPTY) =
        IptvSearchSourceSignature.of(accounts.toList(), overlay)

    @Test
    fun `no enabled playlist has no signature`() {
        assertNull(sig(), "none")
        assertNull(sig(a.copy(enabled = false)), "only disabled")
        assertNotNull(sig(a), "one enabled")
    }

    @Test
    fun `content settings that change results change the signature`() {
        val base = sig(a, b)
        assertNotEquals(base, sig(a.copy(contentTypes = a.contentTypes - CONTENT_TYPE_MOVIES), b), "movies switched off")
        assertNotEquals(base, sig(a.copy(categorySelections = CategorySelections(live = listOf("12"))), b), "category selection narrowed")
        assertNotEquals(
            sig(a.copy(categorySelections = CategorySelections(live = null))),
            sig(a.copy(categorySelections = CategorySelections(live = emptyList()))),
            "deselect all differs from all",
        )
        assertNotEquals(base, sig(a, b.copy(enabled = false)), "playlist disabled")
        assertNotEquals(sig(a), base, "playlist added")
        assertNotEquals(base, sig(a.copy(username = "other"), b), "re-pointed at another account")
    }

    @Test
    fun `hidden channels and groups change the signature`() {
        val base = sig(a)
        assertNotEquals(base, sig(a, overlay = OverlaySnapshot(channels = mapOf("fp:1:x" to ChannelOverlay(hidden = true)))), "channel hidden")
        assertNotEquals(base, sig(a, overlay = OverlaySnapshot(categories = mapOf("c:1:y" to CategoryOverlay(hidden = true)))), "group hidden")
        assertEquals(
            base,
            sig(a, overlay = OverlaySnapshot(
                channels = mapOf("fp:1:x" to ChannelOverlay(pinned = true, rename = "Mine")),
                categories = mapOf("c:1:y" to CategoryOverlay(position = 2)),
            )),
            "pins, renames and order do not change what search returns",
        )
    }

    @Test
    fun `settings that cannot change results keep the signature`() {
        val base = sig(a, b)
        assertEquals(base, sig(b, a), "playlist order")
        assertEquals(base, sig(a.copy(name = "Renamed"), b), "rename")
        assertEquals(base, sig(a.copy(password = "changed"), b), "password")
        assertEquals(
            base,
            sig(a.copy(epgUrl = "http://epg.test", catchUpTimeCorrectionMinutes = 60, userAgent = "UA", dnsProvider = "google"), b),
            "epg / catch-up / user agent / dns",
        )
        assertEquals(
            sig(a.copy(categorySelections = CategorySelections(movies = listOf("1", "2")))),
            sig(a.copy(categorySelections = CategorySelections(movies = listOf("2", "1")))),
            "category selection order",
        )
    }

    @Test
    fun `the signature never carries credentials as text`() {
        val m3u = a.copy(sourceType = "m3u_url", id = "m3u|http://m.test/get.php?username=u&password=s3cret", baseUrl = "http://m.test/get.php?username=u&password=s3cret")
        val s = sig(m3u, a.copy(password = "s3cret-pass"))!!
        assertFalse(s.contains("s3cret"), "no password text")
        assertFalse(s.contains("m.test"), "no address text")
    }
}
