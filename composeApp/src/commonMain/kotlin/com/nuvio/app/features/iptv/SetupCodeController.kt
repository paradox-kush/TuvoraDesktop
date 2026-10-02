package com.nuvio.app.features.iptv

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the "set up from a code" screens show. Never holds the code itself: that lives in [SetupCodeHolder]. */
internal data class SetupCodeUiState(
    /** The entry field's text (grouped as typed). A code is typed text until Continue holds it in memory. */
    val typed: String = "",
    /** A problem found while checking what was typed (shown under the field). */
    val typedProblem: SetupCodeProblem? = null,
    val previewLoading: Boolean = false,
    /** The last preview attempt: [SetupCodeOutcome.Ready] or the reason it is not. Null before one ran. */
    val previewOutcome: SetupCodeOutcome? = null,
    /** The chosen "Add to" profile index (defaults to the active one). */
    val selectedProfileIndex: Int? = null,
    val redeeming: Boolean = false,
    /** Why the redeem was refused, shown on the preview page. */
    val redeemRefusal: SetupCodeOutcome? = null,
    /** Set once a redeem succeeded; the UI navigates and then calls [SetupCodeController.finish]. */
    val completed: SetupCompletion? = null,
    /** A guest (no account) pressed Continue/Add: ask before signing them out to reach sign-in. */
    val guestPrompt: Boolean = false,
    /** Bumped every time a (new) code is held, so the preview screen restarts its load for it. */
    val holdGeneration: Int = 0,
) {
    val preview: SetupPreview? get() = (previewOutcome as? SetupCodeOutcome.Ready)?.preview

    /** Never prints [typed]: a state that reaches a log line must not carry the code. */
    override fun toString(): String =
        "SetupCodeUiState(previewLoading=$previewLoading, previewOutcome=${previewOutcome?.analyticsName}, " +
            "redeeming=$redeeming, completed=${completed != null}, holdGeneration=$holdGeneration)"
}

/** The result a finished redeem hands the UI: who added what, and which playlist to open. */
internal data class SetupCompletion(
    val providerName: String,
    val profileIndex: Int,
    val added: Int,
    val updated: Int,
    val alreadyRedeemed: Boolean,
    /** The playlist to open: the first one added/updated that this device now holds, else null (list). */
    val openPlaylistKey: String?,
    /** Why services were not installed (`missing_login` / `invalid_url`), if any. */
    val skippedReasons: List<String> = emptyList(),
    /** Services the setup had already installed for this account (a second code for the same package). */
    val unchanged: Int = 0,
) {
    /** What the person should be told. Pure, so every platform says the same thing for the same redeem. */
    val outcome: CompletionKind
        get() = when {
            alreadyRedeemed -> CompletionKind.ALREADY_IN_ACCOUNT
            added + updated > 0 -> CompletionKind.ADDED
            // A second code for a package this account already holds: nothing new, and not "nothing went wrong" either.
            unchanged > 0 -> CompletionKind.ALREADY_IN_ACCOUNT
            "invalid_url" in skippedReasons && "missing_login" !in skippedReasons -> CompletionKind.NOTHING_INVALID_URL
            skippedReasons.isNotEmpty() -> CompletionKind.NOTHING_MISSING_LOGIN
            else -> CompletionKind.NOTHING_ADDED
        }
}

/** The sentence a finished redeem earns. Nothing-added is NOT "added your playlist". */
internal enum class CompletionKind { ADDED, ALREADY_IN_ACCOUNT, NOTHING_MISSING_LOGIN, NOTHING_INVALID_URL, NOTHING_ADDED }

/** What pressing Continue on the code field did. */
internal enum class ContinueResult { OPEN_PREVIEW, NEEDS_SIGN_IN, REJECTED }

/** Who is using the app, as far as a setup code is concerned. Only [REAL] can redeem. */
internal enum class AccountKind {
    REAL,
    /** "Continue without account": on-device only, no server session. Reaching sign-in means signing out of guest mode. */
    GUEST,
    /** Signed out, with the sign-in screen one request away. */
    SIGNED_OUT,
}

internal fun accountKindOf(state: AuthState): AccountKind = when {
    state !is AuthState.Authenticated -> AccountKind.SIGNED_OUT
    state.isAnonymous -> AccountKind.GUEST
    else -> AccountKind.REAL
}

/**
 * The state holder behind the "set up from a code" screens (no Compose, no I/O of its own beyond the
 * injected [ProviderSetupApi]). One shared instance: the Add Playlist page and the Preview page are
 * separate settings pages, and a redeem must outlive the page that started it (it runs on its own scope,
 * so leaving the screen can never cancel it half way).
 *
 * Network rule: one preview request per Continue, one redeem per confirm, nothing on a timer.
 */
internal class SetupCodeController(
    private val api: () -> ProviderSetupApi = { ManagedInfoRefresher.api },
    private val holder: SetupCodeHolder = SetupCodeHolder.shared,
    private val accountKind: () -> AccountKind = { accountKindOf(AuthRepository.state.value) },
    private val activeProfileIndex: () -> Int = { ProfileRepository.activeProfileId },
    private val profileExists: (Int) -> Boolean = { index -> ProfileRepository.state.value.profiles.any { it.profileIndex == index } },
    private val localAccountKeys: () -> Set<String> = { XtreamRepository.uiState.value.accounts.map { it.id }.toSet() },
    private val pullPlaylists: suspend (Int) -> Unit = { XtreamSyncParticipant.pullFromServer(it) },
    private val refreshManaged: suspend (Int) -> Unit = { ManagedInfoRefresher.refresh(it) },
    /** Store builds hide add-ons, so the redeem asks the server not to install them. */
    private val skipAddons: () -> Boolean = { !AppFeaturePolicy.addonsEnabled },
    private val requestSignIn: () -> Unit = { AuthRepository.requestSignIn() },
    private val signOutToSignIn: suspend () -> Unit = { AuthRepository.signOut() },
    private val telemetry: ProviderSetupTelemetry = ProviderSetupTelemetry,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(SetupCodeUiState())
    val state: StateFlow<SetupCodeUiState> = _state.asStateFlow()

    private fun isRealAccount() = accountKind() == AccountKind.REAL

    /** Sends a person without a real account towards sign-in, keeping the code for the way back. */
    private fun routeToSignIn() {
        resumeAfterSignIn = true
        when (accountKind()) {
            AccountKind.REAL -> resumeAfterSignIn = false
            // Reaching sign-in from guest mode signs the guest out (it wipes guest data), so ask first.
            AccountKind.GUEST -> _state.update { it.copy(guestPrompt = true) }
            AccountKind.SIGNED_OUT -> requestSignIn()
        }
    }

    /** The guest agreed: sign out of guest mode, which opens the sign-in screen. The code stays in memory. */
    fun confirmGuestSignIn() {
        _state.update { it.copy(guestPrompt = false) }
        scope.launch { signOutToSignIn() }
    }

    fun dismissGuestPrompt() {
        resumeAfterSignIn = false
        _state.update { it.copy(guestPrompt = false) }
    }

    private var previewJob: Job? = null
    private var redeemJob: Job? = null

    /** The code the CURRENT preview was fetched for: confirm() redeems only a code the person was shown. */
    private var previewedCode: String? = null

    /** Incremented whenever a held code changes; a preview result from an older value is discarded. */
    private var previewSeq = 0

    /** A different code is now held: whatever was loading or shown belongs to the old one. */
    private fun supersedePreview() {
        previewSeq++
        previewJob?.cancel()
        previewJob = null
        previewedCode = null
    }

    /** Set when Continue routed the person to sign-in: the shell reopens the preview once they are back. */
    private var resumeAfterSignIn = false

    fun onTyped(raw: String) {
        _state.update { it.copy(typed = SetupCode.liveFormat(raw), typedProblem = null) }
    }

    /**
     * Paste: a code, or a link/message that contains one. Anything else is placed as typed so the person
     * sees what was pasted and the usual problem sentence explains it.
     */
    fun onPasted(clipboardText: String) {
        val code = SetupCode.extractFromLink(clipboardText) ?: clipboardText
        onTyped(code)
    }

    /** A code arrived from a link (https://tuvora.co/s/<code>). True when it was a code (and is now held). */
    fun acceptLinkedCode(codeOrLink: String): Boolean {
        val code = SetupCode.extractFromLink(codeOrLink) ?: SetupCode.parse(codeOrLink) ?: return false
        supersedePreview()
        // A link while the preview page is open must restart ITS load: the generation keys that effect.
        _state.value = SetupCodeUiState(typed = SetupCode.format(code), holdGeneration = _state.value.holdGeneration + 1)
        if (!holder.set(code)) return false
        if (!isRealAccount()) routeToSignIn()
        return true
    }

    /**
     * A link arrived while there was no shell to open it in (the profile picker): keep the code in memory only
     * (the same holder, the same 30 minute bound) with no routing or sign-in side effects.
     */
    fun holdLinkedCode(codeOrLink: String): Boolean {
        val code = SetupCode.extractFromLink(codeOrLink) ?: SetupCode.parse(codeOrLink) ?: return false
        return holder.set(code)
    }

    /** The shell appeared: continue the held link as if it had just arrived. False when nothing is held any more. */
    fun acceptHeldCode(): Boolean {
        val code = holder.peek() ?: return false
        return acceptLinkedCode(code)
    }

    /** Continue on the code field. Holds the code in memory; a person without a real account signs in first. */
    fun onContinue(): ContinueResult {
        when (val parsed = SetupCode.normalize(_state.value.typed)) {
            is SetupCodeParse.Invalid -> {
                _state.update { it.copy(typedProblem = parsed.problem) }
                return ContinueResult.REJECTED
            }
            is SetupCodeParse.Valid -> {
                holder.set(parsed.code)
                supersedePreview()
                _state.update {
                    it.copy(
                        previewOutcome = null, previewLoading = false, redeemRefusal = null, completed = null,
                        typedProblem = null, holdGeneration = it.holdGeneration + 1,
                    )
                }
            }
        }
        if (!isRealAccount()) {
            routeToSignIn()
            return ContinueResult.NEEDS_SIGN_IN
        }
        return ContinueResult.OPEN_PREVIEW
    }

    /** True once (then false) when the person went to sign in with a code held and should land on the preview. */
    fun takeResumeAfterSignIn(): Boolean {
        if (!resumeAfterSignIn || !isRealAccount() || !holder.hasCode()) return false
        resumeAfterSignIn = false
        return true
    }

    fun hasHeldCode(): Boolean = holder.hasCode()

    /** Loads the preview for the held code (a no-op if one is loading, or already Ready for it). */
    fun loadPreview(force: Boolean = false) {
        val code = holder.peek()
        if (code == null) {
            // Nothing held: never held, spent, or past the 30 minute bound. The field must not keep showing it,
            // and a dead code's own outcome (expired + its contacts) must not be rewritten to "enter a code".
            _state.update {
                it.copy(typed = "", previewLoading = false, previewOutcome = it.previewOutcome ?: SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY))
            }
            return
        }
        if (previewJob?.isActive == true) return
        if (!force && _state.value.previewOutcome is SetupCodeOutcome.Ready && previewedCode == code) return
        _state.update { it.copy(previewLoading = true, previewOutcome = null, redeemRefusal = null) }
        val seq = ++previewSeq
        previewJob = scope.launch {
            val outcome = api().preview(code)
            // Superseded while in flight (a different code was held meanwhile): its answer is for a code the
            // person is no longer looking at, and must never be shown beside the code that is held now.
            if (seq != previewSeq || holder.peek() != code) return@launch
            telemetry.previewed(outcome)
            previewedCode = if (outcome is SetupCodeOutcome.Ready) code else null
            val dead = outcome is SetupCodeOutcome.Unusable || outcome is SetupCodeOutcome.Expired
            _state.update {
                it.copy(
                    previewLoading = false,
                    previewOutcome = outcome,
                    selectedProfileIndex = it.selectedProfileIndex ?: activeProfileIndex(),
                    // A dead code is not worth holding on to, and is not left in the field either.
                    typed = if (dead) "" else it.typed,
                )
            }
            if (dead) holder.clear()
        }
    }

    fun selectProfile(index: Int) {
        _state.update { it.copy(selectedProfileIndex = index, redeemRefusal = null) }
    }

    /** The "Add to <profile>" button. */
    fun confirm() {
        val preview = _state.value.preview ?: return
        if (_state.value.redeeming) return
        if (!isRealAccount()) {
            routeToSignIn()
            return
        }
        val code = holder.peek() ?: run {
            _state.update { it.copy(typed = "", redeemRefusal = SetupCodeOutcome.Unusable) }
            return
        }
        if (code != previewedCode) {
            // The preview on screen is for another code than the one held: never redeem what was not shown.
            _state.update { it.copy(previewOutcome = null, redeemRefusal = null) }
            loadPreview(force = true)
            return
        }
        val chosen = _state.value.selectedProfileIndex ?: activeProfileIndex()
        val profile = chosen.takeIf(profileExists) ?: activeProfileIndex().takeIf(profileExists)
        if (profile == null) {
            _state.update { it.copy(selectedProfileIndex = null, redeemRefusal = SetupCodeOutcome.ProfileGone) }
            return
        }
        _state.update { it.copy(selectedProfileIndex = profile, redeeming = true, redeemRefusal = null) }
        redeemJob = scope.launch {
            val result = try {
                api().redeem(code, profile, skipAddons())
            } catch (e: CancellationException) {
                throw e
            }
            when (result) {
                is RedeemResult.Redeemed -> onRedeemed(preview, profile, result.summary)
                is RedeemResult.Refused -> onRefused(preview, result.outcome)
            }
        }
    }

    private suspend fun onRedeemed(preview: SetupPreview, profile: Int, summary: RedeemSummary) {
        telemetry.redeemed(summary.added, summary.updated, if (summary.alreadyRedeemed) "already_redeemed" else "redeemed")
        // ONE playlist pull, for the profile that received the playlists. The pull brings the rows in
        // (and refreshes the managed map through the sync participant); another profile's playlists
        // arrive when that profile is next used, so only its managed map is refreshed.
        if (profile == activeProfileIndex()) pullPlaylists(profile) else refreshManaged(profile)
        holder.clear()
        val keys = localAccountKeys()
        val open = summary.playlists.firstOrNull { it.playlistKey in keys && profile == activeProfileIndex() }?.playlistKey
        _state.update {
            it.copy(
                redeeming = false,
                completed = SetupCompletion(
                    providerName = preview.providerName, profileIndex = profile,
                    added = summary.added, updated = summary.updated, alreadyRedeemed = summary.alreadyRedeemed,
                    openPlaylistKey = open, skippedReasons = summary.skippedReasons, unchanged = summary.unchanged,
                ),
            )
        }
    }

    private fun onRefused(preview: SetupPreview, outcome: SetupCodeOutcome) {
        val shown = if (outcome is SetupCodeOutcome.Expired) SetupCodeOutcome.Expired(preview.support) else outcome
        telemetry.capture("setup_code_redeemed", mapOf("added" to 0, "updated" to 0, "outcome" to shown.analyticsName))
        if (shown is SetupCodeOutcome.NeedsSignIn) routeToSignIn()
        // The code is dead for Unusable/Expired: forget it (and the field). Network/rate-limit problems keep it
        // for a retry; a vanished profile is recovered by falling back to the active one on the next confirm.
        val dead = shown is SetupCodeOutcome.Unusable || shown is SetupCodeOutcome.Expired
        if (dead) holder.clear()
        _state.update {
            it.copy(
                redeeming = false,
                redeemRefusal = shown,
                typed = if (dead) "" else it.typed,
                selectedProfileIndex = if (shown is SetupCodeOutcome.ProfileGone) null else it.selectedProfileIndex,
            )
        }
    }

    /** The person pressed Cancel (or left the flow): forget the code and everything about it. */
    fun cancel() {
        supersedePreview()
        holder.clear()
        _state.value = SetupCodeUiState()
        resumeAfterSignIn = false
    }

    /** After the UI has navigated away from a [SetupCompletion]. */
    fun finish() {
        holder.clear()
        _state.value = SetupCodeUiState()
    }

    companion object {
        val shared = SetupCodeController()
    }
}
