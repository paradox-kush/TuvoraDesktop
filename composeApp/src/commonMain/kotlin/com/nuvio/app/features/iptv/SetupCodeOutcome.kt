package com.nuvio.app.features.iptv

/** One playlist a setup would add: its display name and source type, never an address or a login. */
data class SetupPreviewPlaylist(val name: String, val sourceType: String)

/**
 * What a setup code would add, as the public preview route returns it. Allow-list only (the route
 * never sends credentials or URLs; this parser would not carry them if it did). Add-ons are names only.
 */
data class SetupPreview(
    val providerName: String,
    val support: ProviderSupport,
    val packageName: String,
    val playlists: List<SetupPreviewPlaylist>,
    val addons: List<String>,
    val status: String? = null,
    val expiresAt: String? = null,
)

/** The wording a [SetupCodeOutcome] shows. [english] is the approved text; the UI resolves a resource. */
enum class SetupCodeMessage(val english: String) {
    ENTER_CODE("Enter the setup code your provider gave you."),
    BAD_CHARACTERS("That doesn't look like a setup code. Codes use letters and the numbers 2-9 only."),
    WRONG_LENGTH("A code has 12 characters. Check it against the one your provider sent."),
    EXPIRED("This code has expired. Ask your provider for a new one."),
    RATE_LIMITED("Too many tries. Wait a little while and try again."),
    NETWORK("We couldn't reach Tuvora. Check your connection and try again."),
    UNUSABLE(
        "This code can't be used. If you've already used it, the playlist is in your account. " +
            "Otherwise ask your provider for a new code.",
    ),
    PROFILE_GONE("That profile no longer exists. Pick another."),
}

/**
 * The one pure mapping from "what happened to a setup code" to what the person sees, used by BOTH the
 * preview and the redeem. Neutral wording is app-side (decision 6.2): the server deliberately returns
 * distinct used / already_used / revoked / suspended states for its own web page, and the apps do not
 * show the distinction — only `expired` names its cause, and offers the provider's contacts.
 */
sealed interface SetupCodeOutcome {
    data class Ready(val preview: SetupPreview) : SetupCodeOutcome
    /** Not signed in with a real account: route to sign-in/up and keep the code in memory. */
    data object NeedsSignIn : SetupCodeOutcome
    data class Expired(val support: ProviderSupport) : SetupCodeOutcome
    data class RateLimited(val retryAfterSec: Int?) : SetupCodeOutcome
    data object Network : SetupCodeOutcome
    data object Unusable : SetupCodeOutcome
    /** Redeem: the chosen profile was deleted meanwhile — re-show the profile chips. */
    data object ProfileGone : SetupCodeOutcome
    data class Problem(val problem: SetupCodeProblem) : SetupCodeOutcome

    /** The sentence to show, or null for [Ready] / [NeedsSignIn] (which show none). */
    val message: SetupCodeMessage?
        get() = when (this) {
            is Ready, NeedsSignIn -> null
            is Expired -> SetupCodeMessage.EXPIRED
            is RateLimited -> SetupCodeMessage.RATE_LIMITED
            Network -> SetupCodeMessage.NETWORK
            Unusable -> SetupCodeMessage.UNUSABLE
            ProfileGone -> SetupCodeMessage.PROFILE_GONE
            is Problem -> when (problem) {
                SetupCodeProblem.EMPTY -> SetupCodeMessage.ENTER_CODE
                SetupCodeProblem.BAD_CHARACTERS -> SetupCodeMessage.BAD_CHARACTERS
                SetupCodeProblem.WRONG_LENGTH -> SetupCodeMessage.WRONG_LENGTH
            }
        }

    /** The closed analytics vocabulary: an outcome only, never the code, a URL or a provider name. */
    val analyticsName: String
        get() = when (this) {
            is Ready -> "ready"
            NeedsSignIn -> "needs_sign_in"
            is Expired -> "expired"
            is RateLimited -> "rate_limited"
            Network -> "network"
            Unusable -> "unusable"
            ProfileGone -> "profile_gone"
            is Problem -> "problem"
        }

    companion object {
        fun fromProblem(problem: SetupCodeProblem): SetupCodeOutcome = Problem(problem)

        /**
         * A server error code (raised by an RPC, returned as `{ok:false,error}`, or in the preview route's
         * body) -> the outcome. [support] is the provider's contacts when a preview already showed them
         * (so an `expired` redeem can still offer them), else none.
         */
        fun fromErrorCode(code: String?, support: ProviderSupport = ProviderSupport.NONE): SetupCodeOutcome =
            when (code?.trim()?.lowercase()) {
                "expired" -> Expired(support)
                "rate_limited" -> RateLimited(null)
                "anonymous_not_allowed", "not_authenticated" -> NeedsSignIn
                "profile_not_found" -> ProfileGone
                // invalid_code, not_found, used, already_used, revoked, suspended, unavailable,
                // anything else: one neutral answer.
                else -> Unusable
            }

        /**
         * The preview route's HTTP answer -> the outcome of a code that was NOT ready (a 200 with a body
         * is parsed to [Ready] by [SetupPreviewParser]). [errorCode] is the body's `code`, if it had one.
         *
         * A 404 with no code means the feature is off on the server (neutral "can't be used"). A 5xx with
         * no code at all is a proxy/gateway failure rather than an answer about the code, so it reads as
         * [Network] ("try again"), not as a verdict on the code.
         */
        fun fromPreviewHttp(status: Int, errorCode: String?, retryAfterSec: Int?): SetupCodeOutcome = when {
            status == 429 || errorCode == "rate_limited" -> RateLimited(retryAfterSec)
            errorCode != null -> fromErrorCode(errorCode)
            status in 500..599 -> Network
            else -> Unusable
        }
    }
}
