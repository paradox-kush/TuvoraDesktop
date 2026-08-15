package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the poster-queue head contract that iOS build 119 (1.4.28) died on: [PosterEnricher.drain]
 * took the head entry, removed it from the queue, and then read the entry's value. On the JVM that
 * is harmless; on Kotlin/Native a `MutableMap.MutableEntry` is invalidated the moment its backing
 * entry is removed, and the read aborts the process with SIGABRT — 30 crashes / 10 users in a week,
 * every one of them right after the IPTV tab opened.
 *
 * **This test only proves something on Kotlin/Native.** The JVM host run
 * (`:composeApp:androidHostTest`) passes against the unsafe implementation too, which is exactly
 * why the bug shipped. Run it via `:composeApp:iosSimulatorArm64Test`. A regression does not fail
 * an assertion there — it kills the test process, so the signal is a crashed run, not a red assert.
 */
class PosterQueueHeadTest {

    /** The crashing read itself: the value must outlive the entry it came from. */
    @Test
    fun `head value is readable after its entry leaves the map`() {
        val queue = LinkedHashMap<String, String>()
        queue["acct|movie|1"] = "poster-1"

        val head = removeHead(queue)

        assertEquals("poster-1", head)
        assertTrue(queue.isEmpty())
    }

    /**
     * drain() holds its request across a suspending artwork fetch while the other worker keeps
     * removing — so heads are read long after the map has moved on, several removals later.
     */
    @Test
    fun `earlier heads survive later removals`() {
        val queue = LinkedHashMap<String, String>()
        repeat(8) { queue["k$it"] = "v$it" }

        val firstWorker = removeHead(queue)
        val secondWorker = removeHead(queue)
        while (removeHead(queue) != null) Unit

        assertEquals("v0", firstWorker)
        assertEquals("v1", secondWorker)
        assertTrue(queue.isEmpty())
    }

    /** Insertion order is the whole point of the queue: the visible window fills before prefetch. */
    @Test
    fun `drains in insertion order`() {
        val queue = LinkedHashMap<String, Int>()
        repeat(50) { queue["k$it"] = it }

        val drained = buildList { while (true) add(removeHead(queue) ?: break) }

        assertEquals((0 until 50).toList(), drained)
    }

    /** An empty queue ends the worker; it must not throw. */
    @Test
    fun `empty queue yields null`() {
        assertNull(removeHead(LinkedHashMap<String, String>()))
    }
}
