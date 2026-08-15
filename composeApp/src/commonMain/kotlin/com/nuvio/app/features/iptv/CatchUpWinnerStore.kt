package com.nuvio.app.features.iptv

/**
 * The disk-backed [CatchUpDialectWalk.WinnerMemory] — "what shape this panel answered last time".
 *
 * The proof rides on the playlist itself ([XtreamAccount.catchUpWinner]) rather than a side table:
 * a dialect is a fact about the PANEL, so it is worth carrying to the user's other devices exactly
 * like their other playlist options, and the account already has an additive-with-defaults
 * persistence story that needs no migration.
 *
 * ## The preference fork this closes
 *
 * [CatchUpDialectWalk] leads its walk with a remembered winner. That is right until the viewer
 * flips the per-playlist container preference: an account that already learned TS would keep
 * leading with TS under an m3u8-first walk, so the setting would be visibly inert on precisely the
 * accounts whose owner has used catch-up before and gone looking for the scrub bar.
 *
 * So the proof records the preference it was won under, and [recall] declines to offer it when the
 * preference has since changed. Bypassing rather than deleting is deliberate: the walk simply
 * starts from the top of the newly-ordered ladder and re-learns, and nothing has to reach into the
 * walk's internals to make the setting live.
 */
internal class CatchUpWinnerStore(
    private val accountOf: (accountId: String) -> XtreamAccount?,
    private val update: (accountId: String, edit: (XtreamAccount) -> XtreamAccount) -> Unit,
) : CatchUpDialectWalk.WinnerMemory {

    override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? {
        val account = accountOf(accountId) ?: return null
        val stored = account.catchUpWinner ?: return null
        // Proven under a different container preference — the ladder is reordered, so the proof
        // no longer says anything about which shape should lead.
        if (stored.preferM3u8 != account.catchUpPreferM3u8) return null
        // A name written by a newer build (or corrupted) reads as "nothing learned", never a throw.
        val dialect = CatchUpDialectWalk.Dialect.entries.firstOrNull { it.name == stored.dialect }
            ?: return null
        return CatchUpDialectWalk.StoredWinner(stored.formatsSignature, dialect)
    }

    override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) {
        val account = accountOf(accountId) ?: return
        val next = CatchUpWinner(
            formatsSignature = winner.formatsSignature,
            dialect = winner.dialect.name,
            preferM3u8 = account.catchUpPreferM3u8,
        )
        // Re-proving what is already stored must not write: every account edit is a sync push, and
        // a successful replay re-proves its winner every single time.
        if (account.catchUpWinner == next) return
        update(accountId) { it.copy(catchUpWinner = next) }
    }
}
