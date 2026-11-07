package com.nuvio.app.core.telemetry.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUncaughtDispatcherTest {
    private class Recorder {
        val fatal = ArrayList<String>()
        val nonFatal = ArrayList<Triple<String, String, List<String>>>()
        val dispatcher = DesktopUncaughtDispatcher(
            onFatal = { thread, _ -> fatal += thread },
            onNonFatal = { type, thread, frames -> nonFatal += Triple(type, thread, frames) },
        )
    }

    @Test
    fun mainThreadDeathIsTheCrashRecord() {
        val r = Recorder()
        r.dispatcher.dispatch("main", IllegalStateException("boom"))
        assertEquals(listOf("main"), r.fatal)
        assertTrue(r.nonFatal.isEmpty())
    }

    @Test
    fun backgroundThreadExceptionsNeverWriteACrashRecord() {
        val r = Recorder()
        r.dispatcher.dispatch("OkHttp Dispatcher", RuntimeException("GET http://panel.example.com/live?token=abc"))
        r.dispatcher.dispatch("AWT-EventQueue-0", IllegalArgumentException("x"))
        assertTrue(r.fatal.isEmpty(), "a non-fatal exception must not overwrite or create pending_crash.json")
        assertEquals(2, r.nonFatal.size)
        // The message never travels: only the type and frames.
        assertFalse(r.nonFatal.flatMap { it.third }.any { it.contains("panel.example.com") })
    }

    @Test
    fun aNonFatalCannotShadowALaterFatal() {
        val r = Recorder()
        r.dispatcher.dispatch("worker-1", RuntimeException("a"))
        r.dispatcher.dispatch("main", IllegalStateException("fatal"))
        r.dispatcher.dispatch("worker-1", RuntimeException("b"))
        assertEquals(listOf("main"), r.fatal)
    }

    @Test
    fun nonFatalsAreDeduplicatedPerTypeAndCappedPerRun() {
        val r = Recorder()
        repeat(10) { r.dispatcher.dispatch("w", RuntimeException("same type")) }
        assertEquals(1, r.nonFatal.size)
        val types = listOf(
            IllegalStateException(), IllegalArgumentException(), UnsupportedOperationException(),
            NumberFormatException(), ArithmeticException(), IndexOutOfBoundsException(), NullPointerException(),
        )
        types.forEach { r.dispatcher.dispatch("w", it) }
        assertEquals(DesktopUncaughtDispatcher.MAX_NON_FATAL_PER_RUN, r.nonFatal.size)
    }

    @Test
    fun aThrowingSinkNeverEscapesTheHandler() {
        val d = DesktopUncaughtDispatcher(onFatal = { _, _ -> error("sink down") }, onNonFatal = { _, _, _ -> error("sink down") })
        d.dispatch("main", RuntimeException())
        d.dispatch("w", RuntimeException())
    }
}
