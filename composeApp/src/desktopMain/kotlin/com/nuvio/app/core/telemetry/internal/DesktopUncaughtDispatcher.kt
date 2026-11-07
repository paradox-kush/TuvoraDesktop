package com.nuvio.app.core.telemetry.internal

/**
 * On the JVM an uncaught exception ends only the thread it happened on. Recording every one as a crash made a
 * background hiccup (OkHttp, a coroutine worker, the AWT event thread) overwrite `pending_crash.json` and report
 * `app_crash_report` for an app that never crashed. So:
 *  - the `main` thread dying is what ends a Compose Desktop app (the application loop runs on it), so only that
 *    is a crash record;
 *  - every other thread is a NON-FATAL event of its own (type, thread, frames; no message), deduplicated per
 *    type and capped per run, which can never overwrite a fatal record.
 */
internal class DesktopUncaughtDispatcher(
    private val onFatal: (thread: String, error: Throwable) -> Unit,
    private val onNonFatal: (type: String, thread: String, frames: List<String>) -> Unit,
    private val maxNonFatalPerRun: Int = MAX_NON_FATAL_PER_RUN,
) {
    private val seenTypes = HashSet<String>()
    private var sent = 0

    fun dispatch(threadName: String, error: Throwable) {
        try {
            if (isFatalThread(threadName)) {
                onFatal(threadName, error)
                return
            }
            val type = CrashRecordCodec.exceptionType(error)
            val admit = synchronized(this) {
                if (sent >= maxNonFatalPerRun || !seenTypes.add(type)) false else { sent++; true }
            }
            if (admit) onNonFatal(type, threadName.take(48), CrashRecordCodec.framesOf(error))
        } catch (_: Throwable) {
            // Capture must never replace the real failure.
        }
    }

    companion object {
        const val MAX_NON_FATAL_PER_RUN = 5
        fun isFatalThread(threadName: String): Boolean = threadName == "main"
    }
}
