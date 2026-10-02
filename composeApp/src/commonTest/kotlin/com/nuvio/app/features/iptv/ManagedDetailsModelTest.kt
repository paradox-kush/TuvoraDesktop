package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The details-screen state builder (contract section 8): header facts, expiry display, action groups. */
class ManagedDetailsModelTest {
    private val now = 1_800_000_000L
    private val day = 86_400L
    private val xtream = XtreamAccount(id = "k", name = "Acme", baseUrl = "http://panel.example.com", username = "u", password = "p")
    private val m3u = xtream.copy(sourceType = SOURCE_TYPE_M3U_URL)
    private val info = ManagedInfo(
        playlistKey = "k", providerName = "Acme TV",
        support = ProviderSupport(telegram = "acme_tv", whatsapp = "+44 7700 900123", email = "help@acme.example.com"),
        serviceUpdatedAt = "2026-10-01T10:00:00Z",
    )
    private val panel = XtreamAccountInfo(status = "active", isTrial = false, expiresAtEpochSec = now + 12 * day, maxConnections = 2, activeConnections = 1)

    private fun build(
        account: XtreamAccount = xtream, info: ManagedInfo? = this.info, panel: XtreamAccountInfo? = this.panel, allowEdit: Boolean = true,
    ) = ManagedDetailsModel.build(account, info, panel, DetailsCounts(10, 20, 30), now, allowEdit = allowEdit)

    @Test
    fun `a managed playlist shows the ribbon and the provider and only the contacts it set`() {
        val model = build()
        assertEquals("Managed by Acme TV", model.managedBy)
        assertEquals("Acme TV", model.providerName)
        assertEquals("2026-10-01T10:00:00Z", model.serviceUpdatedAt)
        // The contract's order (and the web's normalizeSupport): WhatsApp, Telegram, Email, Website — one order on every platform.
        assertEquals(listOf(ContactKind.WHATSAPP, ContactKind.TELEGRAM, ContactKind.EMAIL), model.contacts.map { it.kind })
        assertEquals(listOf("https://wa.me/447700900123", "https://t.me/acme_tv", "mailto:help@acme.example.com"), model.contacts.map { it.url })
        assertTrue(model.lockedServerLogin)
        assertTrue(model.isManaged)
    }

    @Test
    fun `an unmanaged playlist has no ribbon and no contacts and no lock`() {
        val model = build(info = null)
        assertNull(model.managedBy)
        assertEquals(emptyList(), model.contacts)
        assertEquals(false, model.lockedServerLogin)
        assertEquals(false, model.isManaged)
    }

    @Test
    fun `days left are whole days rounded up and the thin bar shows only in the last thirty days`() {
        assertEquals(ExpiryDisplay.DaysLeft(12, 0.4f), build().expiry)
        assertEquals(ExpiryDisplay.DaysLeft(1, 1f / 30f), ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now + 3 * 3600), now))
        // The bar rule (apply on every platform): days <= 30 -> fraction = days / 30 (so 30 days is a full bar);
        // more than 30 days -> NO bar at all (null), only the "N days left" text.
        assertEquals(ExpiryDisplay.DaysLeft(30, 1f), ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now + 30 * day), now))
        assertEquals(ExpiryDisplay.DaysLeft(31, null), ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now + 31 * day), now))
        assertEquals(ExpiryDisplay.DaysLeft(90, null), ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now + 90 * day), now))
        assertEquals(null, (ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now + 90 * day), now) as ExpiryDisplay.DaysLeft).fraction)
    }

    @Test
    fun `a count of zero is hidden until the index knows better`() {
        fun counts(c: DetailsCounts) = ManagedDetailsModel.build(xtream, info, panel, c, now).counts
        // Not indexed yet reads as 0 for Xtream: never shown as "0 Movies / 0 Series".
        assertEquals(DetailsCounts(null, null, null), counts(DetailsCounts(0, 0, 0)))
        assertTrue(counts(DetailsCounts(0, 0, 0)).isEmpty, "the whole row is hidden, not three zero tiles")
        // Only the counts that are known and positive show; the rest stay hidden.
        assertEquals(DetailsCounts(null, 5, null), counts(DetailsCounts(0, 5, 0)))
        assertEquals(DetailsCounts(12, 5, 9), counts(DetailsCounts(12, 5, 9)))
        assertEquals(DetailsCounts(null, null, null), counts(DetailsCounts(null, -1, null)), "a negative or unknown count is hidden")
        assertEquals(DetailsCounts(4, null, null), counts(DetailsCounts(4, null, null)))
    }

    @Test
    fun `no expiry means no bar and the right words`() {
        assertEquals(ExpiryDisplay.NotReported, ManagedDetailsModel.expiryOf(null, now), "panel not reached")
        assertEquals(ExpiryDisplay.NotReported, ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = null), now))
        assertEquals(ExpiryDisplay.NeverExpires, ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = 0L), now))
        assertEquals(ExpiryDisplay.Expired, ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = now - 5), now))
        assertEquals(ExpiryDisplay.Text("February 20, 2027"), ManagedDetailsModel.expiryOf(panel.copy(expiresAtEpochSec = null, expiresText = "February 20, 2027"), now))
    }

    @Test
    fun `a failed panel check is not reported as the provider having no expiry`() {
        // The details screen's own build: the panel was asked and did not answer.
        val failed = ManagedDetailsModel.build(xtream, info, null, DetailsCounts(), now, panelCheckFailed = true)
        assertEquals(ExpiryDisplay.CheckFailed, failed.expiry)
        // An answered call that carried no expiry is the provider's own statement.
        val noExpiry = ManagedDetailsModel.build(xtream, info, panel.copy(expiresAtEpochSec = null), DetailsCounts(), now, panelCheckFailed = false)
        assertEquals(ExpiryDisplay.NotReported, noExpiry.expiry)
        // Still loading / nothing asked yet: not a failure.
        assertEquals(ExpiryDisplay.NotReported, ManagedDetailsModel.build(xtream, info, null, DetailsCounts(), now).expiry)
        // An answer always wins over a stale failure flag.
        assertEquals(ExpiryDisplay.DaysLeft(12, 0.4f), ManagedDetailsModel.build(xtream, info, panel, DetailsCounts(), now, panelCheckFailed = true).expiry)
    }

    @Test
    fun `connections show only when the panel reports a maximum`() {
        assertEquals(ConnectionsDisplay(1, 2), build().connections)
        assertNull(build(panel = panel.copy(maxConnections = null)).connections)
        assertNull(build(panel = null).connections)
        assertEquals(ConnectionsDisplay(0, 1), build(panel = panel.copy(maxConnections = 1, activeConnections = null)).connections)
    }

    @Test
    fun `status carries the trial tag`() {
        assertEquals("Active", build().statusText)
        assertEquals("Active · Trial", build(panel = panel.copy(isTrial = true)).statusText)
        assertNull(build(panel = null).statusText)
    }

    @Test
    fun `a managed playlist's groups are the provider's and your library and and remove with Detach`() {
        val groups = build().groups
        assertEquals(listOf(DetailsGroupKind.PROVIDER, DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), groups.map { it.kind })
        assertEquals("ACME TV", groups[0].title)
        assertEquals(listOf(DetailsAction.CONTACT), groups[0].actions)
        assertEquals(
            listOf(DetailsAction.CONTENT_CATEGORIES, DetailsAction.HIDDEN, DetailsAction.REMATCH, DetailsAction.EDIT, DetailsAction.TOGGLE_ENABLED),
            groups[1].actions,
        )
        assertEquals(listOf(DetailsAction.DETACH, DetailsAction.REMOVE), groups[2].actions)
    }

    @Test
    fun `an unmanaged playlist has no provider group and no Detach but still has Remove`() {
        val groups = build(info = null).groups
        assertEquals(listOf(DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), groups.map { it.kind })
        assertEquals(listOf(DetailsAction.REMOVE), groups[1].actions)
    }

    @Test
    fun `a provider with no contacts has no contact group`() {
        val groups = build(info = info.copy(support = ProviderSupport.NONE)).groups
        assertEquals(listOf(DetailsGroupKind.LIBRARY, DetailsGroupKind.REMOVE), groups.map { it.kind })
    }

    @Test
    fun `re-match is Xtream only and TV leaves out Edit`() {
        assertEquals(false, DetailsAction.REMATCH in build(account = m3u).groups[1].actions)
        assertEquals(false, DetailsAction.EDIT in build(allowEdit = false).groups[1].actions)
    }

    @Test
    fun `contacts are only what passes the shape rules`() {
        val support = ProviderSupport(
            whatsapp = "12", telegram = "ab", email = "x@y?cc=z@w.co", website = "javascript:alert(1)",
        )
        assertEquals(emptyList(), support.links())
        val ok = ProviderSupport(website = "https://acme.example.com/help", telegram = "https://t.me/acme_tv").links()
        assertEquals(listOf("https://t.me/acme_tv", "https://acme.example.com/help"), ok.map { it.url })
        assertEquals("acme.example.com", ok[1].text)
    }

    @Test
    fun `a website contact is https and a public-looking host only`() {
        fun site(v: String) = ProviderContacts.website(v)
        assertEquals("https://acme.example.com/help?x=1", site("https://acme.example.com/help?x=1"))
        assertEquals("https://acme.example.com:8443/", site("https://acme.example.com:8443/"))
        assertNull(site("http://acme.example.com"), "plain http is not offered as a tappable contact")
        assertNull(site("https://192.168.0.1/"), "an IP literal")
        assertNull(site("https://10.0.0.1:8080/x"))
        assertNull(site("https://[::1]/"), "an IPv6 literal")
        assertNull(site("https://localhost/"), "a single-label host")
        assertNull(site("https://intranet/"), "a single-label host")
        assertNull(site("https://xn--e1afmkfd.example.com/"), "punycode (IDN) hosts are not shown as links here")
        assertNull(site("https://аcme.example.com/"), "a non-ASCII host")
        assertNull(site("https://user:pw@acme.example.com/"), "no userinfo")
        assertNull(site("https://acme..example.com/"))
        assertNull(site("https://-acme.example.com/"))
        assertNull(site("https://.acme.example.com/"))
        assertNull(site("https://acme.example.1/"), "a numeric TLD")
        assertNull(site("javascript:alert(1)"))
        assertNull(site("intent://scan/#Intent;scheme=zxing;end"))
    }
}
