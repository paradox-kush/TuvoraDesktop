package com.nuvio.app.features.announcements.internal

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.AnnouncementKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Who is looking at Home, as far as announcement visibility cares. */
internal sealed interface AnnouncementSignIn {
    /** Auth has not settled yet (cold start): nothing is known about the account. */
    data object Resolving : AnnouncementSignIn

    /** Signed out, or a local-only (anonymous) profile: there is no account to judge by. */
    data object NoAccount : AnnouncementSignIn

    /** A real account. Either fact may be unknown (null) — an unknown fact never hides a notice. */
    data class Account(val createdAtMs: Long?, val acceptedTermsVersion: String?) : AnnouncementSignIn
}

/** [installFirstSeenAtMs] is when this install first ran the announcements feature (0 = before the marker existed). */
internal data class AnnouncementViewer(val signIn: AnnouncementSignIn, val installFirstSeenAtMs: Long)

/** The signed-in user's facts as the local auth session holds them. */
internal data class AnnouncementAccountRecord(val userId: String, val createdAtMs: Long?, val acceptedTermsVersion: String?)

/**
 * Pure decision: which announcements this viewer should see (UX84).
 *
 * A `policy` notice ("We've updated our Privacy Policy") speaks to people who used Tuvora under the
 * older terms. It is NOT shown to:
 *  - an account created at or after the notice's `starts_at`, or whose sign-up terms version is
 *    dated on or after that day — they agreed to the current terms when they signed up;
 *  - a signed-out / local-only install first opened at or after `starts_at` — it never ran under
 *    the older terms.
 * Everyone else keeps seeing it until they dismiss it. The account decides when there is one, so
 * an existing account on a brand-new device still gets the notice. Unknown facts never hide it, and
 * while sign-in is still resolving it is held back so it does not appear and then vanish.
 * Other kinds are unaffected.
 */
internal object AnnouncementVisibilityPolicy {

    fun isVisible(announcement: Announcement, viewer: AnnouncementViewer): Boolean {
        if (announcement.kind != AnnouncementKind.Policy) return true
        val startsAt = parseInstant(announcement.startsAt) ?: return true
        val startsAtMs = startsAt.toEpochMilliseconds()
        return when (val signIn = viewer.signIn) {
            AnnouncementSignIn.Resolving -> false
            AnnouncementSignIn.NoAccount -> viewer.installFirstSeenAtMs < startsAtMs
            is AnnouncementSignIn.Account -> {
                val signedUpAfter = signIn.createdAtMs?.let { it >= startsAtMs } == true
                val acceptedNewerTerms = termsDate(signIn.acceptedTermsVersion)
                    ?.let { it >= utcDate(startsAt) } == true
                !signedUpAfter && !acceptedNewerTerms
            }
        }
    }

    /** First announcement in server order that is not dismissed, has a title, and is meant for [viewer]. */
    fun pick(items: List<Announcement>, dismissedIds: Set<String>, viewer: AnnouncementViewer): Announcement? =
        AnnouncementPolicy.pick(items.filter { isVisible(it, viewer) }, dismissedIds)

    /**
     * When this install was first seen. A stored value wins; with none, an install that already holds
     * announcement state from an older version counts as existing (0), and a truly fresh one is "now".
     */
    fun installFirstSeenAtMs(storedMs: Long?, hasEarlierState: Boolean, nowMs: Long): Long = when {
        storedMs != null -> storedMs
        hasEarlierState -> 0L
        else -> nowMs
    }

    /** Maps the app's auth state plus the session's user record to what the policy needs. */
    fun signInFrom(state: AuthState, account: AnnouncementAccountRecord?): AnnouncementSignIn = when (state) {
        AuthState.Loading -> AnnouncementSignIn.Resolving
        AuthState.Unauthenticated -> AnnouncementSignIn.NoAccount
        is AuthState.Authenticated -> when {
            state.isAnonymous -> AnnouncementSignIn.NoAccount
            account == null || account.userId != state.userId -> AnnouncementSignIn.Account(null, null)
            else -> AnnouncementSignIn.Account(account.createdAtMs, account.acceptedTermsVersion)
        }
    }

    /** The `terms_version` the app writes into sign-up metadata (a "yyyy-MM-dd" string), if present. */
    fun termsVersionFrom(userMetadata: JsonObject?): String? {
        val value = userMetadata?.get("terms_version") as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseInstant(text: String): Instant? =
        text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** "yyyy-MM-dd" of [instant] in UTC; [Instant.toString] is ISO-8601 in UTC. */
    private fun utcDate(instant: Instant): String = instant.toString().take(DATE_LENGTH)

    private fun termsDate(version: String?): String? =
        version?.trim()?.takeIf { DATE.matches(it) }

    private const val DATE_LENGTH = 10
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
