package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ordering rule for per-tile now/next fetches.
 *
 * Field report (S24, 2026-08-16, debug build): after a hard scroll through a Stalker portal the
 * IPTV section was unusable for ~30 s — every tile that had EVER scrolled into view held its place
 * in the 2-permit line, so the tiles on screen NOW waited behind hundreds that were long gone.
 * Both references agree on the cure: iptvnator's preview queue is rebuilt from the visible tiles on
 * every sync ("request count must track user engagement, not render size"); our own PosterEnricher
 * already puts the visible window in front and ages the tail out.
 *
 * So the backlog is newest-first with a hard cap: the tile the user just revealed runs next, and
 * when the cap overflows the OLDEST pending tile is evicted — with notice, so its once-only guard
 * can be released and a revisit fetches it after all.
 */
class TileEpgBacklogTest {

    @Test
    fun `the newest enqueue runs before older pending work`() {
        val backlog = TileEpgBacklog(cap = 10)
        backlog.addFront("a")
        backlog.addFront("b")
        backlog.addFront("c")
        assertEquals("c", backlog.next(), "the tile revealed last is fetched first")
        assertEquals("b", backlog.next())
        assertEquals("a", backlog.next())
        assertNull(backlog.next())
    }

    @Test
    fun `re-revealing a pending tile moves it to the front`() {
        val backlog = TileEpgBacklog(cap = 10)
        backlog.addFront("a")
        backlog.addFront("b")
        backlog.addFront("a")
        assertEquals("a", backlog.next())
        assertEquals("b", backlog.next())
        assertEquals(0, backlog.size)
    }

    @Test
    fun `overflow evicts the oldest pending tile and says so`() {
        val backlog = TileEpgBacklog(cap = 3)
        assertNull(backlog.addFront("a"))
        assertNull(backlog.addFront("b"))
        assertNull(backlog.addFront("c"))
        // Over the cap: the OLDEST pending tile ages out and is returned so its once-only
        // guard can be released — a revisited tile must be fetchable again.
        assertEquals("a", backlog.addFront("d"), "the oldest pending tile ages out")
        assertEquals("b", backlog.addFront("e"))
        assertEquals(3, backlog.size)
        assertEquals("e", backlog.next(), "newest-first survives the eviction")
    }
}
