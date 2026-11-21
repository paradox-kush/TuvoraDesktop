package com.nuvio.app.core.telemetry.internal

/** What the shutdown hook does, with its collaborators injected so a test can drive it against a fake host. */
internal object DesktopExitHook {
    const val GRACE_MS = 750L

    fun run(
        sendHealthNow: () -> Unit,
        finalize: () -> Unit,
        flush: () -> Unit,
        close: () -> Unit,
        graceMs: Long = GRACE_MS,
    ) {
        DesktopSyncSender.shuttingDown = true
        // The only event that exists solely at exit goes out HERE, on the hook thread, before the grace period and
        // close. Run from a coroutine (as onBackground does) it raced the grace period and lost. Bounded by
        // DesktopSyncSender's timeouts; never throws.
        runCatching(sendHealthNow)
        runCatching(finalize)
        runCatching(flush)
        runCatching { Thread.sleep(graceMs) }
        runCatching(close)
    }
}
