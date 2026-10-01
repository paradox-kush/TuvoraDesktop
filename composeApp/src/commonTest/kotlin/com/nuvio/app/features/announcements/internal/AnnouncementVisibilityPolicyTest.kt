package com.nuvio.app.features.announcements.internal

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.AnnouncementKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** UX84: a "We've updated our Privacy Policy" notice must not greet people who never saw the old one. */
class AnnouncementVisibilityPolicyTest {

    // The wire format PostgREST returns for a timestamptz column.
    private val startsAtWire = "2026-09-28T06:00:00.123456+00:00"
    private val startsAtMs = Instant.parse("2026-09-28T06:00:00.123456Z").toEpochMilliseconds()
    private val hour = 60L * 60 * 1000

    private fun item(id: String, kind: AnnouncementKind, startsAt: String = startsAtWire) = Announcement(
        id = id,
        title = "Title $id",
        body = "Body $id",
        ctaLabel = null,
        ctaUrl = null,
        kind = kind,
        startsAt = startsAt,
    )

    private val policy = item("p", AnnouncementKind.Policy)
    private val news = item("n", AnnouncementKind.Update)

    private fun viewer(signIn: AnnouncementSignIn, installFirstSeenAtMs: Long = 0L) =
        AnnouncementViewer(signIn, installFirstSeenAtMs)

    private fun account(createdAtMs: Long?, terms: String? = null) = AnnouncementSignIn.Account(createdAtMs, terms)

    // --- (a) accounts created under the current terms ---

    @Test
    fun `policy notice is hidden from an account created after it started`() {
        val v = viewer(account(createdAtMs = startsAtMs + hour))
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is hidden from an account created exactly when it started`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(createdAtMs = startsAtMs))))
    }

    @Test
    fun `policy notice is hidden when the accepted terms version is from the day it started or later`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(null, terms = "2026-09-28"))))
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(null, terms = "2026-10-15"))))
    }

    // --- (c) existing users keep seeing it ---

    @Test
    fun `policy notice is shown to an account created before it started`() {
        val v = viewer(account(createdAtMs = startsAtMs - hour, terms = "2026-08-04"))
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is shown to an account created before it on a brand new install`() {
        // A new device for an existing account: the account decides and not the install.
        val v = viewer(account(createdAtMs = startsAtMs - hour), installFirstSeenAtMs = startsAtMs + hour)
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is shown when the account creation time is unknown`() {
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(createdAtMs = null))))
    }

    @Test
    fun `an unparseable terms version does not hide the notice`() {
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(startsAtMs - hour, terms = "v2"))))
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(startsAtMs - hour, terms = ""))))
    }

    // --- (b) installs that never ran under the old terms ---

    @Test
    fun `policy notice is hidden on a signed out install first opened after it started`() {
        val v = viewer(AnnouncementSignIn.NoAccount, installFirstSeenAtMs = startsAtMs + hour)
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is shown on a signed out install that predates it`() {
        val v = viewer(AnnouncementSignIn.NoAccount, installFirstSeenAtMs = startsAtMs - hour)
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, v))
        // Installs from before the first-seen marker existed are stored as 0 and count as existing.
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(AnnouncementSignIn.NoAccount, 0L)))
    }

    @Test
    fun `policy notice waits while sign in is still resolving`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(AnnouncementSignIn.Resolving)))
    }

    @Test
    fun `a policy notice with an unreadable start time is shown rather than guessed away`() {
        val broken = item("b", AnnouncementKind.Policy, startsAt = "")
        assertTrue(AnnouncementVisibilityPolicy.isVisible(broken, viewer(account(startsAtMs + hour))))
    }

    // --- other kinds are untouched ---

    @Test
    fun `non policy announcements ignore account age and install age`() {
        assertTrue(AnnouncementVisibilityPolicy.isVisible(news, viewer(account(startsAtMs + hour))))
        assertTrue(AnnouncementVisibilityPolicy.isVisible(news, viewer(AnnouncementSignIn.NoAccount, startsAtMs + hour)))
        assertTrue(AnnouncementVisibilityPolicy.isVisible(news, viewer(AnnouncementSignIn.Resolving)))
    }

    @Test
    fun `pick falls through a hidden policy notice to the next announcement`() {
        val v = viewer(account(createdAtMs = startsAtMs + hour))
        assertEquals("n", AnnouncementVisibilityPolicy.pick(listOf(policy, news), emptySet(), v)?.id)
        assertNull(AnnouncementVisibilityPolicy.pick(listOf(policy, news), setOf("n"), v))
    }

    // --- install first-seen marker ---

    @Test
    fun `a stored first seen time is kept`() {
        assertEquals(123L, AnnouncementVisibilityPolicy.installFirstSeenAtMs(123L, hasEarlierState = true, nowMs = 999L))
    }

    @Test
    fun `a fresh install is first seen now`() {
        assertEquals(999L, AnnouncementVisibilityPolicy.installFirstSeenAtMs(null, hasEarlierState = false, nowMs = 999L))
    }

    @Test
    fun `an install with announcement state from an older version counts as existing`() {
        assertEquals(0L, AnnouncementVisibilityPolicy.installFirstSeenAtMs(null, hasEarlierState = true, nowMs = 999L))
    }

    // --- mapping the auth state ---

    @Test
    fun `auth state maps to the sign in the policy needs`() {
        val record = AnnouncementAccountRecord(userId = "u1", createdAtMs = 42L, acceptedTermsVersion = "2026-09-27")
        assertEquals(AnnouncementSignIn.Resolving, AnnouncementVisibilityPolicy.signInFrom(AuthState.Loading, record))
        assertEquals(AnnouncementSignIn.NoAccount, AnnouncementVisibilityPolicy.signInFrom(AuthState.Unauthenticated, record))
        assertEquals(
            AnnouncementSignIn.NoAccount,
            AnnouncementVisibilityPolicy.signInFrom(AuthState.Authenticated("anon", null, isAnonymous = true), null),
        )
        assertEquals(
            AnnouncementSignIn.Account(42L, "2026-09-27"),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.Authenticated("u1", "a@b.c", isAnonymous = false), record),
        )
    }

    @Test
    fun `a session user that is not the signed in account contributes nothing`() {
        val other = AnnouncementAccountRecord(userId = "someone-else", createdAtMs = 42L, acceptedTermsVersion = "2026-09-27")
        assertEquals(
            AnnouncementSignIn.Account(null, null),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.Authenticated("u1", null, isAnonymous = false), other),
        )
        assertEquals(
            AnnouncementSignIn.Account(null, null),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.Authenticated("u1", null, isAnonymous = false), null),
        )
    }

    @Test
    fun `terms version is read from sign up metadata`() {
        assertEquals("2026-09-27", AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("terms_version", "2026-09-27") }))
        assertNull(AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("other", "x") }))
        assertNull(AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("terms_version", JsonPrimitive(5)) }))
        assertNull(AnnouncementVisibilityPolicy.termsVersionFrom(null))
    }
}
