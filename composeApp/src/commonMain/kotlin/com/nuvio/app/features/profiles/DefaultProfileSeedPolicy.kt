package com.nuvio.app.features.profiles

/**
 * Whether a local-only session (anonymous / "Continue Without Account") should have one default
 * profile seeded so it drops straight into the app instead of the empty "Who's watching?" grid.
 *
 * Kept pure so the decision tests without Supabase, storage, or Compose. The caller supplies the
 * localized name and default colour; this only decides *whether* to seed.
 *
 *  - **Local-only only.** A signed-in account's profiles come from the backend (`sync_pull_profiles`);
 *    seeding a local profile for it would fork state and race the pull. [AuthState.isLocalOnly] is the
 *    correct guard (true for anonymous, false for a real account) — not `!isAnonymous`, which also
 *    treats Loading/Unauthenticated as "real".
 *  - **Idempotent.** Never seed when a profile already exists, so a restart or a manual profile does
 *    not spawn a second default.
 */
internal object DefaultProfileSeedPolicy {
    fun shouldSeed(isLocalOnly: Boolean, existingProfileCount: Int): Boolean =
        isLocalOnly && existingProfileCount == 0
}
