package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ordering alone was not enough. A channel the panel has no guide for answers empty every time and
 * is asked again on every scroll past — on a lineup where the panel fills 6% of `epg_channel_id`
 * that is a permanent stream of requests that can never succeed, which is how an IP gets blocked
 * at a provider edge. And work queued for a playlist the viewer has left should not drain into a
 * screen that moved on.
 */
class TileEpgAdmissionTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `an unknown channel is admitted`() {
        assertTrue(TileEpgAdmission().admits("ch1", now))
    }

    @Test
    fun `a channel that answered nothing is not re-asked immediately`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        assertFalse(admission.admits("ch1", now + 1_000), "one second later is still the same scroll")
        assertFalse(admission.admits("ch1", now + 59_000))
    }

    @Test
    fun `the cooldown expires so a recovering panel is picked up without a restart`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        assertTrue(admission.admits("ch1", now + TileEpgAdmission.FAILURE_COOLDOWN_MS))
    }

    @Test
    fun `a cooldown is per channel and does not spill onto its neighbours`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        assertTrue(admission.admits("ch2", now), "only the silent channel is held back")
    }

    @Test
    fun `an answer clears the cooldown at once`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        admission.recordAnswered("ch1")
        assertTrue(admission.admits("ch1", now + 1))
    }

    @Test
    fun `a clock that jumped backwards must not pin a channel shut`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        assertTrue(admission.admits("ch1", now - 60L * 60 * 1000), "a backwards clock is not a cooldown")
    }

    @Test
    fun `failure memory is capped by insertion order`() {
        val admission = TileEpgAdmission(cap = 3)
        listOf("a", "b", "c").forEach { admission.recordEmpty(it, now) }
        admission.recordEmpty("d", now)   // evicts "a"
        assertTrue(admission.admits("a", now + 1), "the oldest memory aged out")
        assertFalse(admission.admits("d", now + 1), "the newest is still held")
    }

    @Test
    fun `work queued before an invalidation is retired`() {
        val admission = TileEpgAdmission()
        val queuedAt = admission.currentGeneration
        assertTrue(admission.accepts(queuedAt))
        admission.invalidate()
        assertFalse(admission.accepts(queuedAt), "a playlist switch retires what the old one queued")
        assertTrue(admission.accepts(admission.currentGeneration))
    }

    @Test
    fun `invalidation also forgets cooldowns so a new mapping gets a fresh verdict`() {
        val admission = TileEpgAdmission()
        admission.recordEmpty("ch1", now)
        admission.invalidate()
        assertTrue(admission.admits("ch1", now + 1), "a rebuilt mapping may well have an answer now")
    }

    @Test
    fun `generation only moves forward on invalidation`() {
        val admission = TileEpgAdmission()
        assertEquals(0, admission.currentGeneration)
        admission.recordEmpty("ch1", now)
        admission.recordAnswered("ch1")
        assertEquals(0, admission.currentGeneration, "ordinary traffic must not retire the backlog")
    }
}
