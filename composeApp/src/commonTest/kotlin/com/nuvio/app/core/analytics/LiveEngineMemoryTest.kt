package com.nuvio.app.core.analytics

import com.nuvio.app.core.analytics.LiveRecoveryCoordinator.Engine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveEngineMemoryTest {

    @BeforeTest fun setUp() = LiveEngineMemory.clearAll()
    @AfterTest fun tearDown() = LiveEngineMemory.clearAll()

    @Test
    fun `an unlearned channel has no preference`() {
        assertNull(
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
            "a channel we have never escalated must start on the default engine",
        )
    }

    @Test
    fun `a learned channel is remembered so the next open skips the freeze`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertEquals(
            Engine.MPV,
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
            "the next open must start directly on the learned engine",
        )
    }

    @Test
    fun `live and catch-up lanes are learned independently`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertNull(
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.CATCHUP),
            "a channel's catch-up recording must not inherit its live engine (F6)",
        )
    }

    @Test
    fun `forget clears a learned channel - the re-validation hook`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE)
        assertNull(LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE))
    }

    @Test
    fun `a blank channel id is ignored - never mis-keyed`() {
        LiveEngineMemory.remember("", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertNull(LiveEngineMemory.preferredEngine("", LiveEngineMemory.Lane.LIVE))
        assertNull(LiveEngineMemory.preferredEngine(null, LiveEngineMemory.Lane.LIVE))
    }

    @Test
    fun `snapshot then restore round-trips the learnings for the persistence store`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        LiveEngineMemory.remember("xtream:etv", LiveEngineMemory.Lane.CATCHUP, Engine.MPV)
        val snap = LiveEngineMemory.snapshot()
        LiveEngineMemory.clearAll()
        assertNull(LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE))
        LiveEngineMemory.restore(snap)
        assertEquals(
            Engine.MPV,
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
            "a learned channel must survive a restart via snapshot/restore",
        )
        assertEquals(Engine.MPV, LiveEngineMemory.preferredEngine("xtream:etv", LiveEngineMemory.Lane.CATCHUP))
    }

    @Test
    fun `onChange fires on remember and forget so the store can persist`() {
        var changes = 0
        LiveEngineMemory.onChange = { changes++ }
        try {
            LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
            LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE)
            LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE) // no-op
            assertEquals(2, changes, "write-through fires once per real change, not on a no-op forget")
        } finally {
            LiveEngineMemory.onChange = null
        }
    }
}
