package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A local-only ("Continue Without Account") session with no profile must be given one default
 * profile so it drops straight into the app instead of an empty "Who's watching?" grid. A signed-in
 * account gets its profiles from the backend (never a local seed), and a session that already has a
 * profile is left alone.
 */
class DefaultProfileSeedPolicyTest {

    @Test
    fun `a fresh anonymous session with no profile is seeded`() {
        assertTrue(
            DefaultProfileSeedPolicy.shouldSeed(isLocalOnly = true, existingProfileCount = 0),
            "an anonymous viewer with zero profiles must not be left on the empty grid",
        )
    }

    @Test
    fun `an anonymous session that already has a profile is not re-seeded`() {
        assertFalse(
            DefaultProfileSeedPolicy.shouldSeed(isLocalOnly = true, existingProfileCount = 1),
            "seeding is idempotent — never add a second default profile",
        )
    }

    @Test
    fun `a signed-in account with no local profiles is never seeded`() {
        assertFalse(
            DefaultProfileSeedPolicy.shouldSeed(isLocalOnly = false, existingProfileCount = 0),
            "a real account's profiles come from the backend pull, not a local seed (would fork state)",
        )
    }

    @Test
    fun `a signed-in account with profiles is not seeded`() {
        assertFalse(
            DefaultProfileSeedPolicy.shouldSeed(isLocalOnly = false, existingProfileCount = 3),
        )
    }
}
