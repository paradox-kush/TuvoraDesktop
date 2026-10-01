package com.nuvio.app.features.streams

import com.nuvio.app.core.contracts.StreamSourceGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B63 on Mobile/Desktop: one unreachable playlist held the whole source list (loading chips,
 * auto-play, the player's Next Episode / source fetch) open for its connect timeout — 60 s per
 * panel call on these clients. Virtual time, fakes only, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvMatchSourceLaneTest {

    private val deadHostMs = 60_000L

    private fun source(id: String) = StreamSourceGroup(sourceId = "xtream-match:$id", addonName = id)

    private fun streamFrom(src: StreamSourceGroup) =
        StreamItem(name = src.addonName, url = "http://panel/${src.addonName}.mkv", addonName = src.addonName, addonId = src.sourceId)

    @Test
    fun theSourceListStopsLoadingAtTheBudgetInsteadOfWaitingForADeadPlaylist() = runTest {
        // The shape both fetchers run: one addon + a healthy playlist + a dead playlist, concurrently;
        // the list stops loading (isAnyLoading = false) when the last group has finished.
        val loading = mutableSetOf("addon", "healthy", "dead")
        var listSettledAt = -1L
        val groups = mutableMapOf<String, AddonStreamGroup>()
        fun finished(id: String) {
            loading -= id
            if (loading.isEmpty()) listSettledAt = currentTime
        }

        val othersSettled = Job()
        val addon = launch { delay(2_000); finished("addon") }
        launch { addon.join(); othersSettled.complete() }
        listOf(source("healthy") to 1_500L, source("dead") to deadHostMs).forEach { (src, latency) ->
            launch {
                val group = IptvMatchSourceLane.resolveGroup(src, othersSettled) {
                    delay(latency); listOf(streamFrom(src))
                }
                groups[src.addonName] = group
                finished(src.addonName)
            }
        }
        advanceUntilIdle()

        assertEquals(IptvSourceWaitPolicy.PLAYLIST_BUDGET_MS, listSettledAt, "list stops loading at the budget, not at the dead host's timeout")
        assertEquals(1, groups.getValue("healthy").streams.size, "healthy playlist still delivered")
        val dead = groups.getValue("dead")
        assertFalse(dead.isLoading, "the cut playlist's chip stops loading")
        assertTrue(dead.streams.isEmpty(), "the cut playlist contributes nothing to this list")
        assertNull(dead.error, "a cut is not reported as a request failure")
    }

    @Test
    fun aSlowPlaylistStillMakesTheListWhileAnAddonIsLoadingAnyway() = runTest {
        val othersSettled = Job()
        launch { delay(20_000); othersSettled.complete() }
        val src = source("slow")
        val group = IptvMatchSourceLane.resolveGroup(src, othersSettled) { delay(12_000); listOf(streamFrom(src)) }
        assertEquals(1, group.streams.size, "kept: the list was still waiting on an addon")
        assertEquals(12_000L, currentTime, "delivered when it answered")
    }

    @Test
    fun aFailingPlaylistStillReportsItsError() = runTest {
        val othersSettled = Job().also { it.complete() }
        val group = IptvMatchSourceLane.resolveGroup(source("refused"), othersSettled) {
            delay(100); throw IllegalStateException("refused")
        }
        assertEquals("refused", group.error, "the failure reaches the group")
        assertFalse(group.isLoading, "failed group is finished")
    }
}
