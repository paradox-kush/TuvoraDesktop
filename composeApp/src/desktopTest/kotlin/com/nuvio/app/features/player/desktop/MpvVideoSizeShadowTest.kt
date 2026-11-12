package com.nuvio.app.features.player.desktop

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 500 ms snapshot runs on the UI thread; the video size it reports must come from a shadow
 * refreshed off that thread, never from a blocking mpv read made by the caller.
 */
class MpvVideoSizeShadowTest {
    private var reads = 0
    private var size = IntSize(1920, 804)
    private val queued = mutableListOf<Runnable>()
    private val shadow = MpvVideoSizeShadow(read = { reads++; size })

    @Test
    fun `reading never calls the blocking reader on the caller's thread`() {
        assertEquals(IntSize.Zero, shadow.current(queued::add), "nothing cached yet")
        assertEquals(0, reads, "the snapshot caller made no mpv read")
        assertEquals(1, queued.size, "one refresh handed to the worker")
    }

    @Test
    fun `the worker refresh feeds later reads`() {
        shadow.current(queued::add)
        queued.removeAt(0).run()
        assertEquals(1, reads)
        assertEquals(IntSize(1920, 804), shadow.current(queued::add))
        assertEquals(1, reads, "still no read on the caller's thread")
    }

    @Test
    fun `refreshes never stack while one is in flight`() {
        repeat(5) { shadow.current(queued::add) }
        assertEquals(1, queued.size, "a slow mpv core must not pile up refreshes")
        queued.removeAt(0).run()
        shadow.current(queued::add)
        assertEquals(1, queued.size, "the next tick schedules again once the last one finished")
    }

    @Test
    fun `a failed refresh keeps the last size and frees the slot`() {
        shadow.current(queued::add)
        queued.removeAt(0).run()
        val failing = MpvVideoSizeShadow(read = { error("mpv gone") })
        failing.current(queued::add)
        queued.removeAt(0).run()
        assertEquals(IntSize.Zero, failing.current(queued::add))
        assertEquals(1, queued.size, "slot released after the failure")
    }

    @Test
    fun `reset clears the cached size`() {
        shadow.current(queued::add)
        queued.removeAt(0).run()
        shadow.reset()
        queued.clear()
        assertEquals(IntSize.Zero, shadow.current(queued::add))
    }

    @Test
    fun `a rejected schedule frees the slot`() {
        shadow.current { throw java.util.concurrent.RejectedExecutionException("worker stopped") }
        shadow.current(queued::add)
        assertEquals(1, queued.size)
    }
}
