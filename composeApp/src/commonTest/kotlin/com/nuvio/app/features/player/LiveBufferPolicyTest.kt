package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveBufferPolicyTest {

    @Test
    fun autoKeepsTheEngineDefault() {
        assertNull(LiveBufferPolicy.plan(LiveBufferPolicy.AUTO))
        assertNull(LiveBufferPolicy.plan(null))
    }

    @Test
    fun secondsBecomeAnExoPlayerPlanWithAFastStart() {
        val plan = LiveBufferPolicy.plan(20)
        assertEquals(LiveBufferPlan(minBufferMs = 20_000, maxBufferMs = 40_000, startMs = 1_500, rebufferMs = 10_000), plan)
        assertEquals(LiveBufferPlan(5_000, 10_000, 1_500, 5_000), LiveBufferPolicy.plan(5))
    }

    @Test
    fun onlyLiveNotCatchUpOrVodGetsThePlan() {
        assertEquals(LiveBufferPolicy.plan(30), LiveBufferPolicy.planFor(30, isLive = true, isCatchUp = false))
        assertNull(LiveBufferPolicy.planFor(30, isLive = true, isCatchUp = true))
        assertNull(LiveBufferPolicy.planFor(30, isLive = false, isCatchUp = false))
    }

    @Test
    fun storedValuesSnapOntoTheOfferedChoices() {
        assertEquals(10, LiveBufferPolicy.normalize(12))
        assertEquals(60, LiveBufferPolicy.normalize(600))
        assertEquals(5, LiveBufferPolicy.normalize(3))
        assertEquals(LiveBufferPolicy.AUTO, LiveBufferPolicy.normalize(-4))
    }

    @Test
    fun mpvCapsReadaheadAndSetsTheRebufferCushion() {
        assertEquals(
            listOf("cache" to "yes", "cache-secs" to "30", "demuxer-readahead-secs" to "30", "cache-pause-wait" to "10"),
            LiveBufferPolicy.mpvProperties(LiveBufferPolicy.plan(30)!!),
        )
    }
}
