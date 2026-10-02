package com.nuvio.app.features.streams

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B63: one unreachable playlist (60 s connect timeout per panel call here) held every source list
 * open until it timed out. Same rules as TV's IptvSourceWaitPolicyTest; kotlin.test argument order
 * (expected, actual, message). Virtual time, fakes only — no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvSourceWaitPolicyTest {

    private val budget = IptvSourceWaitPolicy.PLAYLIST_BUDGET_MS
    private val deadHostMs = 60_000L

    private fun settled(): CompletableJob = Job().also { it.complete() }

    @Test
    fun theSourceListSettlesAtTheBudgetInsteadOfWaitingForADeadPlaylist() = runTest {
        val others = Job()
        val delivered = mutableListOf<String>()
        val addon = launch { delay(2_000); delivered += "addon"; others.complete() }
        val playlists = listOf("healthy" to 1_500L, "dead" to deadHostMs).map { (name, latency) ->
            async {
                IptvSourceWaitPolicy.await(others) { delay(latency); name }?.also { delivered += it }
            }
        }
        playlists.awaitAll()
        addon.join()

        assertEquals(budget, currentTime, "list completes at the budget, not at the dead host's timeout")
        assertEquals(listOf("healthy", "addon"), delivered, "healthy sources still delivered")
    }

    @Test
    fun aDeadPlaylistIsAbandonedAtTheBudgetAndItsRequestIsCancelled() = runTest {
        var cancelled = false
        val result = IptvSourceWaitPolicy.await(settled()) {
            try {
                delay(deadHostMs); "late"
            } catch (c: CancellationException) {
                cancelled = true; throw c
            }
        }
        runCurrent()
        assertNull(result, "abandoned playlist contributes nothing")
        assertEquals(budget, currentTime, "waited exactly the budget")
        assertTrue(cancelled, "the in-flight fetch is cancelled, not left running")
    }

    @Test
    fun aPlaylistAnsweringWithinTheBudgetIsAlwaysKept() = runTest {
        val result = IptvSourceWaitPolicy.await(settled()) { delay(budget - 1); "ok" }
        assertEquals("ok", result, "result kept")
        assertEquals(budget - 1, currentTime, "no extra waiting")
    }

    @Test
    fun aSlowPlaylistIsKeptWhileOtherSourcesAreStillLoadingAnyway() = runTest {
        val others = Job()
        launch { delay(20_000); others.complete() }
        val result = IptvSourceWaitPolicy.await(others) { delay(12_000); "slow but alive" }
        assertEquals("slow but alive", result, "not dropped: the list was still waiting on an addon")
        assertEquals(12_000L, currentTime, "delivered when it answered")
    }

    @Test
    fun pastTheBudgetAPlaylistIsCutTheMomentTheLastOtherSourceSettles() = runTest {
        val others = Job()
        launch { delay(10_000); others.complete() }
        val result = IptvSourceWaitPolicy.await(others) { delay(deadHostMs); "late" }
        assertNull(result, "cut")
        assertEquals(10_000L, currentTime, "cut when the addons finished")
    }

    @Test
    fun aFailingPlaylistPropagatesItsErrorToTheCaller() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            IptvSourceWaitPolicy.await<String>(settled()) { delay(100); throw IllegalStateException("refused") }
        }
        assertEquals("refused", error.message, "same error")
    }

    @Test
    fun cancellingTheCallerCancelsTheFetch() = runTest {
        var cancelled = false
        val caller = launch {
            IptvSourceWaitPolicy.await(Job()) {
                try {
                    delay(deadHostMs); "late"
                } catch (c: CancellationException) {
                    cancelled = true; throw c
                }
            }
        }
        advanceTimeBy(1_000)
        caller.cancel()
        runCurrent()
        assertTrue(cancelled, "fetch cancelled with its caller")
        assertFalse(caller.isActive, "caller gone")
    }
}
