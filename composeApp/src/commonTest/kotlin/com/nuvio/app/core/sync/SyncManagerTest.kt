package com.nuvio.app.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncManagerTest {

    @Test
    fun `source prerequisites finish before source dependent pulls`() = runBlocking {
        val events = mutableListOf<String>()
        var profileSettingsApplied = false
        var credentialsApplied = false

        runOrderedProfileSync(
            profileId = 7,
            pluginsEnabled = true,
            operations = ProfileSyncOperations(
                pullAddons = { events += "addons" },
                pullPlugins = { events += "plugins" },
                pullProfileSettings = {
                    events += "settings:start"
                    yield()
                    profileSettingsApplied = true
                    events += "settings:end"
                },
                syncProviderCredentials = {
                    assertTrue(profileSettingsApplied)
                    credentialsApplied = true
                    events += "credentials"
                },
                pullLibrary = {
                    assertTrue(profileSettingsApplied)
                    assertTrue(credentialsApplied)
                    events += "library"
                },
                refreshActiveWatchSource = {
                    assertTrue(profileSettingsApplied)
                    assertTrue(credentialsApplied)
                    events += "active-watch-source"
                },
                pullCollections = { events += "collections" },
                pullHomeCatalogSettings = { events += "home-settings" },
            ),
            onFailure = { _, error -> throw error },
        )

        val lastPrerequisite = events.indexOf("settings:end")
        assertTrue(events.indexOf("credentials") > lastPrerequisite)
        assertTrue(events.indexOf("library") > lastPrerequisite)
        assertTrue(events.indexOf("active-watch-source") > lastPrerequisite)
        assertEquals(1, events.count { it == "active-watch-source" })
    }

    @Test
    fun `disabled plugins are skipped without changing sync ordering`() = runBlocking {
        val events = mutableListOf<String>()

        runOrderedProfileSync(
            profileId = 2,
            pluginsEnabled = false,
            operations = recordingOperations(events),
            onFailure = { _, error -> throw error },
        )

        assertTrue("plugins" !in events)
        assertTrue(events.indexOf("settings") < events.indexOf("library"))
        assertTrue(events.indexOf("credentials") < events.indexOf("library"))
        assertTrue(events.indexOf("settings") < events.indexOf("active-watch-source"))
    }

    @Test
    fun `duplicate active request for one profile is coalesced`() = runBlocking {
        val gate = ProfileSyncRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var runCount = 0

        val first = gate.launch(this, profileId = 4) {
            runCount += 1
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        firstStarted.await()

        val duplicate = gate.launch(this, profileId = 4) {
            runCount += 1
        }

        assertEquals(ProfileSyncRequestResult.Started, first)
        assertEquals(ProfileSyncRequestResult.Coalesced, duplicate)
        assertEquals(1, runCount)

        releaseFirst.complete(Unit)
        yield()
        gate.cancel()
    }

    @Test
    fun `new profile replaces stale in flight request`() = runBlocking {
        val gate = ProfileSyncRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()

        gate.launch(this, profileId = 1) {
            firstStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        firstStarted.await()

        val replacement = gate.launch(this, profileId = 2) {
            secondCompleted.complete(Unit)
        }

        assertEquals(ProfileSyncRequestResult.Replaced, replacement)
        firstCancelled.await()
        secondCompleted.await()
        gate.cancel()
    }

    @Test
    fun `failed step is reported by ordered sync result`() = runBlocking {
        val result = runOrderedProfileSync(
            profileId = 3,
            pluginsEnabled = false,
            operations = recordingOperations(mutableListOf()).copy(
                refreshActiveWatchSource = { error("source refresh failed") },
            ),
        )

        assertFalse(result.succeeded)
        assertEquals(setOf(ProfileSyncStep.ActiveWatchSource), result.failedSteps)
    }

    @Test
    fun `failed profile sync does not advance foreground freshness`() {
        val previous = ProfilePullFreshness(
            profileId = 3,
            completedAtEpochMs = 1_000L,
        )
        val failed = previous.record(
            profileId = 3,
            nowEpochMs = 2_000L,
            result = ProfileSyncResult(setOf(ProfileSyncStep.ActiveWatchSource)),
        )
        val succeeded = previous.record(
            profileId = 3,
            nowEpochMs = 2_000L,
            result = ProfileSyncResult(emptySet()),
        )

        // The failure must move the retry cooldown but leave freshness where it was.
        assertEquals(previous.completedAtEpochMs, failed.completedAtEpochMs)
        assertEquals(2_000L, succeeded.completedAtEpochMs)
        assertFalse(
            ProfilePullFreshness()
                .record(
                    profileId = 3,
                    nowEpochMs = 2_000L,
                    result = ProfileSyncResult(setOf(ProfileSyncStep.ActiveWatchSource)),
                )
                .isRecent(profileId = 3, nowEpochMs = 2_001L, minIntervalMs = 1_000L),
        )
        assertTrue(
            succeeded.isRecent(profileId = 3, nowEpochMs = 2_001L, minIntervalMs = 1_000L),
        )
    }

    @Test
    fun `failed cycle holds off the next attempt and success clears the hold`() {
        val failed = ProfilePullFreshness().record(
            profileId = 3,
            nowEpochMs = 1_000L,
            result = ProfileSyncResult(setOf(ProfileSyncStep.Library)),
        )

        assertEquals(1, failed.consecutiveFailures)
        assertTrue(failed.isInRetryBackoff(profileId = 3, nowEpochMs = 1_500L))
        assertFalse(failed.isInRetryBackoff(profileId = 3, nowEpochMs = 6_001L))
        // The hold is per profile; switching profiles must not inherit it.
        assertFalse(failed.isInRetryBackoff(profileId = 4, nowEpochMs = 1_500L))

        val recovered = failed.record(
            profileId = 3,
            nowEpochMs = 7_000L,
            result = ProfileSyncResult(emptySet()),
        )
        assertEquals(0, recovered.consecutiveFailures)
        assertFalse(recovered.isInRetryBackoff(profileId = 3, nowEpochMs = 7_001L))
    }

    @Test
    fun `consecutive failures back off exponentially up to a cap`() {
        assertEquals(0L, syncRetryBackoffMs(0))
        assertEquals(5_000L, syncRetryBackoffMs(1))
        assertEquals(10_000L, syncRetryBackoffMs(2))
        assertEquals(20_000L, syncRetryBackoffMs(3))
        assertEquals(160_000L, syncRetryBackoffMs(SYNC_RETRY_MAX_FAILURE_STEP))
        assertEquals(
            syncRetryBackoffMs(SYNC_RETRY_MAX_FAILURE_STEP),
            syncRetryBackoffMs(SYNC_RETRY_MAX_FAILURE_STEP + 20),
        )

        var freshness = ProfilePullFreshness()
        repeat(3) { attempt ->
            freshness = freshness.record(
                profileId = 3,
                nowEpochMs = 1_000L * attempt,
                result = ProfileSyncResult(setOf(ProfileSyncStep.Library)),
            )
        }
        assertEquals(3, freshness.consecutiveFailures)
    }

    @Test
    fun `auth refusal goes straight to the longest hold`() {
        val refused = ProfilePullFreshness().record(
            profileId = 3,
            nowEpochMs = 0L,
            result = ProfileSyncResult(
                failedSteps = setOf(ProfileSyncStep.Addons),
                authRefused = true,
            ),
        )

        assertEquals(SYNC_RETRY_MAX_FAILURE_STEP, refused.consecutiveFailures)
        assertEquals(
            syncRetryBackoffMs(SYNC_RETRY_MAX_FAILURE_STEP),
            refused.retryNotBeforeEpochMs,
        )
    }

    @Test
    fun `permission denied on one step abandons the rest of the cycle`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runOrderedProfileSync(
            profileId = 3,
            pluginsEnabled = true,
            operations = recordingOperations(events).copy(
                pullAddons = {
                    events += "addons"
                    error("permission denied for function sync_push_addons")
                },
            ),
        )

        assertTrue(result.authRefused)
        // Only the step that actually ran should have executed; everything after is abandoned
        // rather than sending its own doomed RPC.
        assertEquals(listOf("addons"), events)
        assertEquals(ProfileSyncStep.entries.toSet(), result.failedSteps)
    }

    @Test
    fun `an ordinary step failure still lets the rest of the cycle run`() = runBlocking {
        val events = mutableListOf<String>()

        val result = runOrderedProfileSync(
            profileId = 3,
            pluginsEnabled = true,
            operations = recordingOperations(events).copy(
                pullAddons = {
                    events += "addons"
                    error("boom")
                },
            ),
        )

        assertFalse(result.authRefused)
        assertTrue("library" in events)
        assertTrue("collections" in events)
        assertEquals(setOf(ProfileSyncStep.Addons), result.failedSteps)
    }

    @Test
    fun `a refused push is recognised through the exception type`() {
        assertTrue(SyncNotAuthenticatedException().isSyncAuthRefusal())
        assertTrue(
            IllegalStateException(
                "wrapped",
                RuntimeException("permission denied for function sync_pull_profiles"),
            ).isSyncAuthRefusal(),
        )
        assertTrue(RuntimeException("PostgrestRestException: 42501").isSyncAuthRefusal())
        assertFalse(RuntimeException("connection reset by peer").isSyncAuthRefusal())
        // A bare 401 is excluded on purpose: supabase-kt can refresh past it.
        assertFalse(RuntimeException("HTTP 401 Unauthorized").isSyncAuthRefusal())
    }

    private fun recordingOperations(events: MutableList<String>): ProfileSyncOperations =
        ProfileSyncOperations(
            pullAddons = { events += "addons" },
            pullPlugins = { events += "plugins" },
            pullProfileSettings = { events += "settings" },
            syncProviderCredentials = { events += "credentials" },
            pullLibrary = { events += "library" },
            refreshActiveWatchSource = { events += "active-watch-source" },
            pullCollections = { events += "collections" },
            pullHomeCatalogSettings = { events += "home-settings" },
        )
}
