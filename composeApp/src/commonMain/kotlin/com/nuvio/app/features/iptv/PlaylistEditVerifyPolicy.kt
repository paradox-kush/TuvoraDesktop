package com.nuvio.app.features.iptv

/** What a playlist edit does after its provider check: whether it is saved, and what to tell the user. */
internal data class PlaylistEditOutcome(val save: Boolean, val warning: String?)

/**
 * B60 — decides what an edited playlist's live provider check may do to the save.
 *
 * Decision 2026-09-27: **save anyway and warn**. A playlist is user-authored configuration, which
 * Android's offline-first guidance files under lazy writes (write locally first, reconcile later), not
 * under online-only "bank transfer" writes. And the edit that most often fails a check from the
 * current network is exactly the one the user most needs to keep: a provider moving domains (DNS not
 * yet propagated, a WAF, a geo-block). Refusing the save left them with no way to type in the new URL.
 * So a failed check never discards what was typed; it only surfaces the reason on the playlist row.
 *
 * Only an edit that changes how the provider is reached is checked at all ([needsVerify], see
 * [sameConnectionAs]) — renaming or changing the refresh interval never waits on the network.
 * Adding a NEW playlist still requires a passing check; this policy is for edits only.
 */
internal object PlaylistEditVerifyPolicy {
    fun needsVerify(old: XtreamAccount, edited: XtreamAccount): Boolean = !edited.sameConnectionAs(old)

    fun outcome(verifyResult: Result<Unit>): PlaylistEditOutcome {
        val failure = verifyResult.exceptionOrNull() ?: return PlaylistEditOutcome(save = true, warning = null)
        val reason = failure.message?.trim()?.takeIf { it.isNotEmpty() } ?: "no response"
        return PlaylistEditOutcome(save = true, warning = "Saved, but the provider couldn't be checked: $reason")
    }
}

/**
 * B04 — whether an options edit (content types, category picks, catch-up prefs, guide offset, …)
 * changed anything the server stores, and so must be recorded as a pending v2 edit. A device-local
 * preference (never on the wire) must not trigger a push; a synced one must, or the next sync would
 * adopt the server's older row and silently revert it.
 */
internal fun optionEditNeedsSync(old: XtreamAccount, new: XtreamAccount): Boolean =
    playlistSyncKey(old) != playlistSyncKey(new)

/**
 * What a v2 sync applies locally for a pulled/reconciled set: this device's local-only catch-up /
 * guide preferences carried across ([preserveDeviceLocalPrefs]) — the same treatment the v1 pull
 * always gave them. Without it every v2 apply reset those preferences on every row. Ids were already
 * reconciled with this device's by [PlaylistKeyAdoption] when the set was pulled (Step 0).
 */
internal fun v2ApplyLocal(pulled: List<XtreamAccount>, local: List<XtreamAccount>): List<XtreamAccount> =
    preserveDeviceLocalPrefs(pulled, local)
