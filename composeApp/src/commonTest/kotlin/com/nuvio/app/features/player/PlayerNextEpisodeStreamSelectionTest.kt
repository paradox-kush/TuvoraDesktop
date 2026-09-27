package com.nuvio.app.features.player

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerNextEpisodeStreamSelectionTest {
    @Test
    fun `selection timeout keeps waiting while matching source is loading`() {
        val unrelated = stream("Fast", "fast", "other")
        val matching = stream("Current", "current", "current")
        val coordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
            selectPreferred = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
        )
        val partialState = StreamsUiState(
            groups = listOf(
                AddonStreamGroup("Fast", "fast", listOf(unrelated)),
                AddonStreamGroup("Current", "current", emptyList(), isLoading = true),
            ),
            isAnyLoading = true,
        )

        assertEquals(NextEpisodeStreamSelectionDecision.Waiting, coordinator.onStreamsChanged(partialState))
        assertEquals(NextEpisodeStreamSelectionDecision.Waiting, coordinator.onSelectionDelayElapsed(partialState))

        val completedState = StreamsUiState(
            groups = listOf(
                AddonStreamGroup("Fast", "fast", listOf(unrelated)),
                AddonStreamGroup("Current", "current", listOf(matching)),
            ),
        )

        assertEquals(
            NextEpisodeStreamSelectionDecision.Selected(matching),
            coordinator.onStreamsChanged(completedState),
        )
    }

    @Test
    fun `selection timeout uses an available match without waiting for every source`() {
        val matching = stream("Current", "current", "current")
        val coordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
            selectPreferred = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
        )
        val partialState = StreamsUiState(
            groups = listOf(
                AddonStreamGroup("Current", "current", listOf(matching)),
                AddonStreamGroup("Slow", "slow", emptyList(), isLoading = true),
            ),
            isAnyLoading = true,
        )

        assertEquals(
            NextEpisodeStreamSelectionDecision.Selected(matching),
            coordinator.onSelectionDelayElapsed(partialState),
        )
    }

    @Test
    fun `completed source load without a match requires manual selection`() {
        val unrelated = stream("Fast", "fast", "other")
        val coordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
            selectPreferred = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
        )
        val completedState = StreamsUiState(
            groups = listOf(AddonStreamGroup("Fast", "fast", listOf(unrelated))),
        )

        assertEquals(
            NextEpisodeStreamSelectionDecision.ManualSelection,
            coordinator.onStreamsChanged(completedState),
        )
    }

    // Fork: the tiered picker only takes a confident continuation while providers are still
    // answering; the any-fallback tier waits for the delay (or for every provider to answer).
    @Test
    fun `before the delay an unrelated stream is not taken while providers are still answering`() {
        val unrelated = stream("Fast", "fast", "other")
        val coordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> streams.firstOrNull() },
            selectPreferred = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
        )
        val partialState = StreamsUiState(
            groups = listOf(
                AddonStreamGroup("Fast", "fast", listOf(unrelated)),
                AddonStreamGroup("Current", "current", emptyList(), isLoading = true),
            ),
            isAnyLoading = true,
        )

        assertEquals(NextEpisodeStreamSelectionDecision.Waiting, coordinator.onStreamsChanged(partialState))
        assertEquals(
            NextEpisodeStreamSelectionDecision.Selected(unrelated),
            coordinator.onSelectionDelayElapsed(partialState),
        )
    }

    @Test
    fun `a group still loading counts as loading even when the aggregate flag has cleared`() {
        val unrelated = stream("Fast", "fast", "other")
        val coordinator = NextEpisodeStreamSelectionCoordinator(
            selectAfterDelay = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
            selectPreferred = { streams -> streams.firstOrNull { it.behaviorHints.bingeGroup == "current" } },
        )
        val state = StreamsUiState(
            groups = listOf(
                AddonStreamGroup("Fast", "fast", listOf(unrelated)),
                AddonStreamGroup("Current", "current", emptyList(), isLoading = true),
            ),
            isAnyLoading = false,
        )

        assertEquals(NextEpisodeStreamSelectionDecision.Waiting, coordinator.onStreamsChanged(state))
    }

    private fun stream(addonName: String, addonId: String, bingeGroup: String): StreamItem = StreamItem(
        name = addonName,
        url = "https://example.com/$addonId.mp4",
        addonName = addonName,
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup),
    )
}
