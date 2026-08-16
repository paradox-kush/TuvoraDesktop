package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enricher must not keep working for a playlist the user has left.
 *
 * Measured on an S24 with the HubTrace instrumentation (2026-08-16), 30 s AFTER switching from the
 * Stalker portal to an Xtream one: **148 Stalker portal requests, 87 of them the poster enricher**,
 * with `queued=202` still pending at ~700 ms each — roughly two minutes of work for a screen nobody
 * was looking at, running against the same rate-limited host the NEW playlist needed.
 *
 * The switch-abandon added earlier could not help: it drops calls waiting for a portal permit, but
 * the enricher creates each request FRESH after the switch, so every one is legitimately "current".
 * The queue itself has to be scoped — which is what these tests pin.
 */
class PosterEnricherScopeTest {

    @Test
    fun `dropping a provider removes only its queued work`() {
        val queue = LinkedHashMap<String, String>()   // key -> accountId, mirroring the real queue
        queue["stalker|movie|1"] = "stalker"
        queue["stalker|movie|2"] = "stalker"
        queue["bigz|movie|9"] = "bigz"

        val removed = PosterEnricher.dropQueuedFor(queue, keepAccountId = "bigz") { it }

        assertEquals(2, removed, "both stalker entries are dropped")
        assertEquals(listOf("bigz|movie|9"), queue.keys.toList(), "the current provider's work survives")
    }

    @Test
    fun `dropping is a no-op when everything belongs to the current provider`() {
        val queue = LinkedHashMap<String, String>()
        queue["bigz|movie|1"] = "bigz"
        queue["bigz|movie|2"] = "bigz"

        val removed = PosterEnricher.dropQueuedFor(queue, keepAccountId = "bigz") { it }

        assertEquals(0, removed)
        assertEquals(2, queue.size)
    }

    /** A switch to a provider with nothing queued still clears the old backlog. */
    @Test
    fun `switching to an unqueued provider clears everything`() {
        val queue = LinkedHashMap<String, String>()
        queue["stalker|movie|1"] = "stalker"
        queue["stalker|movie|2"] = "stalker"

        val removed = PosterEnricher.dropQueuedFor(queue, keepAccountId = "onnipsite") { it }

        assertEquals(2, removed)
        assertTrue(queue.isEmpty())
    }
}
