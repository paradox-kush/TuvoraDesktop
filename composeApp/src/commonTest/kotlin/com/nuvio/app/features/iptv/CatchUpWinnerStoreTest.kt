package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.CatchUpDialectWalk.Dialect
import com.nuvio.app.features.iptv.CatchUpDialectWalk.StoredWinner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The disk-backed [CatchUpDialectWalk.WinnerMemory], and the one fork it exists to close.
 *
 * The walk remembers the dialect that played and leads with it next time. That is right until the
 * viewer flips the per-playlist container preference: a remembered TS winner would still lead an
 * m3u8-first walk, so the toggle would do nothing at all on exactly the accounts that have used
 * catch-up before — the ones whose owner is most likely to go looking for the scrub bar.
 */
class CatchUpWinnerStoreTest {

    private class FakeAccounts(var account: XtreamAccount) {
        val store = CatchUpWinnerStore(
            accountOf = { id -> account.takeIf { it.id == id } },
            update = { id, edit -> if (account.id == id) account = edit(account) },
        )
    }

    private fun accounts(preferM3u8: Boolean = false) = FakeAccounts(
        XtreamAccount(
            id = "acc-1",
            name = "Panel",
            baseUrl = "http://panel.tv:80",
            username = "u",
            password = "p",
            catchUpPreferM3u8 = preferM3u8,
        )
    )

    /** Nothing learned yet — the walk starts from the top of its ladder. */
    @Test
    fun `an account with no proof recalls nothing`() {
        assertNull(accounts().store.recall("acc-1"))
    }

    /** An unknown account id must not resolve to some other account's proof. */
    @Test
    fun `an unknown account recalls nothing`() {
        val a = accounts()
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_TS))
        assertNull(a.store.recall("acc-2"))
    }

    /** The ordinary path: what played once leads the walk next time. */
    @Test
    fun `a proven winner is recalled`() {
        val a = accounts()
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_TS))
        assertEquals(StoredWinner("unknown", Dialect.PATH_TS), a.store.recall("acc-1"))
    }

    /** The proof survives a round trip through the account model, not just the in-memory object. */
    @Test
    fun `a proven winner is persisted on the account`() {
        val a = accounts()
        a.store.remember("acc-1", StoredWinner("m3u8,ts", Dialect.PHP_STREAMING))
        assertEquals("m3u8,ts", a.account.catchUpWinner?.formatsSignature)
        assertEquals("PHP_STREAMING", a.account.catchUpWinner?.dialect)
        assertEquals(false, a.account.catchUpWinner?.preferM3u8)
    }

    /**
     * THE PIN. A winner proven while the playlist preferred TS must not lead the walk once the
     * viewer asks for m3u8-first — otherwise the setting is inert and the scrub bar never appears.
     */
    @Test
    fun `flipping the container preference bypasses the remembered winner`() {
        val a = accounts(preferM3u8 = false)
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_TS))
        assertEquals(StoredWinner("unknown", Dialect.PATH_TS), a.store.recall("acc-1"))

        a.account = a.account.copy(catchUpPreferM3u8 = true)
        assertNull(a.store.recall("acc-1"))
    }

    /** And the same in reverse — a viewer who turns the preference back off re-learns TS. */
    @Test
    fun `flipping the preference back bypasses an m3u8 winner`() {
        val a = accounts(preferM3u8 = true)
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_M3U8))
        assertEquals(StoredWinner("unknown", Dialect.PATH_M3U8), a.store.recall("acc-1"))

        a.account = a.account.copy(catchUpPreferM3u8 = false)
        assertNull(a.store.recall("acc-1"))
    }

    /** Re-proving the same winner must not churn the account (every write is a sync push). */
    @Test
    fun `re-proving the same winner does not rewrite the account`() {
        val a = accounts()
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_TS))
        val afterFirst = a.account
        a.store.remember("acc-1", StoredWinner("unknown", Dialect.PATH_TS))
        assertEquals(afterFirst, a.account, "an unchanged winner should not rewrite the account")
    }

    /** A junk dialect name persisted by a newer build must read as "no proof", never throw. */
    @Test
    fun `an unparsable stored dialect recalls nothing`() {
        val a = accounts()
        a.account = a.account.copy(
            catchUpWinner = CatchUpWinner(formatsSignature = "unknown", dialect = "PATH_WEBM", preferM3u8 = false)
        )
        assertNull(a.store.recall("acc-1"))
    }
}
