package com.nuvio.app.features.profiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the REAL [ProfileSwitchController.runSwitch] (its side effects injected as fake suspend
 * lambdas), not a hand-rolled model, so these guard the shipped orchestration directly.
 *
 * Two properties are pinned:
 * - **Ordering** — the awaited `switch -> warm -> pull` pipeline runs in that exact order. Awaiting
 *   the switch before the pull is what stops [ProfileRepository]'s `AddonRepository.onProfileChanged`
 *   reset from racing (and wiping) a fast pull.
 * - **Single-flight** — while one switch is in flight the [kotlinx.coroutines.sync.Mutex] is held, so
 *   a concurrent second [ProfileSwitchController.runSwitch] returns `false` and runs none of its
 *   steps. This is the fix for the repeated-tap race: stacking taps used to spin up concurrent
 *   switch/warm/pull pipelines whose overlapping `finally`s flickered the overlay and stampeded the
 *   pull. Once the in-flight switch finishes, a later switch is admitted again.
 *
 * Runs on BOTH the JVM host runner and the Kotlin/Native (iOS simulator) runner — the single-flight
 * guarantee has to hold on native's memory model too. Backticked names avoid commas (native quirk).
 */
class ProfileSwitchSequencingTest {

    @Test
    fun `runSwitch runs switch then warm then pull in order`() = runBlocking {
        val events = mutableListOf<String>()

        val result = ProfileSwitchController.runSwitch(
            profileIndex = 3,
            switchProfile = { events += "switch" },
            warm = { events += "warm" },
            pull = { events += "pull" },
        )

        assertEquals(true, result, "a lone switch should perform the switch")
        assertEquals(
            listOf("switch", "warm", "pull"),
            events,
            "steps must run strictly in switch -> warm -> pull order",
        )
    }

    @Test
    fun `switchingTo reflects the target during the switch and clears after`() = runBlocking {
        assertEquals(null, ProfileSwitchController.switchingTo.value, "idle before any switch")

        val observedDuring = CompletableDeferred<Int?>()
        val result = ProfileSwitchController.runSwitch(
            profileIndex = 7,
            switchProfile = { observedDuring.complete(ProfileSwitchController.switchingTo.value) },
            warm = {},
            pull = {},
        )

        assertEquals(true, result, "the switch should be performed")
        assertEquals(7, observedDuring.await(), "switchingTo exposes the target while the switch runs")
        assertEquals(null, ProfileSwitchController.switchingTo.value, "switchingTo clears once the switch returns")
    }

    @Test
    fun `a concurrent runSwitch is rejected while one is in flight then admitted after`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        // First switch parks inside switchProfile, holding the single-flight lock.
        val first = launch {
            ProfileSwitchController.runSwitch(
                profileIndex = 1,
                switchProfile = {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                },
                warm = {},
                pull = {},
            )
        }

        firstEntered.await() // the first switch now holds the lock, suspended mid switchProfile

        // A second switch fires WHILE the first is in flight: it must be rejected and run nothing,
        // and it must not clobber the in-flight target published on switchingTo.
        val secondSteps = mutableListOf<String>()
        val secondResult = ProfileSwitchController.runSwitch(
            profileIndex = 2,
            switchProfile = { secondSteps += "switch" },
            warm = { secondSteps += "warm" },
            pull = { secondSteps += "pull" },
        )

        assertEquals(false, secondResult, "a switch already in flight must reject the concurrent call")
        assertEquals(emptyList<String>(), secondSteps, "the rejected switch must run none of its steps")
        assertEquals(1, ProfileSwitchController.switchingTo.value, "switchingTo stays the in-flight target")

        // Release the first; it completes and frees the lock.
        releaseFirst.complete(Unit)
        first.join()
        assertEquals(null, ProfileSwitchController.switchingTo.value, "switchingTo clears after the switch")

        // A later switch (after the first finished) is admitted again and runs the full pipeline.
        val laterSteps = mutableListOf<String>()
        val laterResult = ProfileSwitchController.runSwitch(
            profileIndex = 4,
            switchProfile = { laterSteps += "switch" },
            warm = { laterSteps += "warm" },
            pull = { laterSteps += "pull" },
        )

        assertEquals(true, laterResult, "once the in-flight switch finishes a new switch is admitted")
        assertEquals(
            listOf("switch", "warm", "pull"),
            laterSteps,
            "the later admitted switch runs all steps in order",
        )
    }
}
