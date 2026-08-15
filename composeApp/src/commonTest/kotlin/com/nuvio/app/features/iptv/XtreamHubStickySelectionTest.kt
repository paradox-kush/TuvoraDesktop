package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fix 1 — sticky provider selection. The hub used to reset to the first account whenever its
 * in-memory state was fresh (cold start, or any resetForProfile): `selectedAccountId` starts null
 * and fell straight to `accounts.firstOrNull()`. These pin the restore policy: the last-picked
 * provider and section tab come back, but a provider that is gone or disabled falls back to the
 * first ENABLED account instead of resurrecting. (Category + scroll state stay session-only.)
 */
class XtreamHubStickySelectionTest {

    private fun account(id: String, enabled: Boolean = true) = XtreamAccount(
        id = id,
        name = "Panel $id",
        baseUrl = "http://$id.example:8080",
        username = "u",
        password = "p",
        enabled = enabled,
    )

    @Test
    fun `the remembered provider is restored on entry`() {
        val accounts = listOf(account("a"), account("b"), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "b", accounts = accounts)

        assertEquals("b", selected, "a fresh entry must land on the remembered provider - not the first")
    }

    @Test
    fun `a removed account falls back to the first enabled`() {
        // The remembered playlist was deleted (or synced away) since the last visit.
        val accounts = listOf(account("a"), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "gone", accounts = accounts)

        assertEquals("a", selected, "a remembered id with no matching account must fall back")
    }

    @Test
    fun `a disabled account falls back to the first enabled`() {
        // The remembered playlist still exists but was toggled off — and so was the FIRST one,
        // so the fallback must be first ENABLED, not first overall.
        val accounts = listOf(account("a", enabled = false), account("b", enabled = false), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "b", accounts = accounts)

        assertEquals("c", selected, "a disabled remembered account must not be resurrected")
    }

    @Test
    fun `an in-session selection wins over the remembered provider`() {
        // ensureLoaded re-runs on every hub entry; a live in-memory pick must survive it.
        val accounts = listOf(account("a"), account("b"), account("c"))

        val selected = resolveStickyAccount(current = "c", remembered = "b", accounts = accounts)

        assertEquals("c", selected, "the in-memory selection must win over the persisted one")
    }

    @Test
    fun `an in-session selection that vanished falls back to the first enabled`() {
        // The selected playlist was deleted while the hub was open.
        val accounts = listOf(account("a"), account("b"))

        val selected = resolveStickyAccount(current = "gone", remembered = "gone", accounts = accounts)

        assertEquals("a", selected, "a dead in-session selection must fall back like before the fix")
    }

    @Test
    fun `no enabled accounts leaves nothing selected`() {
        val allDisabled = listOf(account("a", enabled = false))

        assertNull(
            resolveStickyAccount(current = null, remembered = "a", accounts = allDisabled),
            "with every playlist disabled there is nothing to select",
        )
        assertNull(
            resolveStickyAccount(current = null, remembered = null, accounts = emptyList()),
            "an empty account list selects nothing",
        )
    }

    @Test
    fun `the section tab is restored`() {
        val restored = resolveStickySection(remembered = "SERIES", fallback = XtreamHubSection.LIVE)

        assertEquals(XtreamHubSection.SERIES, restored, "the last-used section tab must come back on entry")
    }

    @Test
    fun `an unknown section name falls back to the default`() {
        // A name written by a newer build (or corrupted) reads as "nothing remembered", never a throw.
        assertEquals(
            XtreamHubSection.LIVE,
            resolveStickySection(remembered = "GARBAGE", fallback = XtreamHubSection.LIVE),
            "junk section names must fall back to the default tab",
        )
        assertEquals(
            XtreamHubSection.LIVE,
            resolveStickySection(remembered = null, fallback = XtreamHubSection.LIVE),
            "no remembered section means the default tab",
        )
    }

    @Test
    fun `a stored selection round-trips through json`() {
        val stored = XtreamHubSelection(accountId = "http://host:8080|u1", section = "MOVIES")

        val parsed = parseHubSelection(encodeHubSelection(stored))

        assertEquals(stored, parsed, "the persisted selection must decode back to itself")
    }

    @Test
    fun `junk selection json reads as nothing remembered`() {
        assertNull(parseHubSelection("not json at all"), "corrupted storage must read as empty - never a throw")
        assertNull(parseHubSelection(null), "absent storage must read as empty")
        assertNull(parseHubSelection(""), "blank storage must read as empty")
    }
}
