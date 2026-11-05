package com.nuvio.app.features.recordings.internal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.api.RecordingAlarms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The desktop [RecordingAlarms]: an in-app scheduler, so a recording starts only while the app is
 * open (the executor's stated rule on desktop). One coroutine per armed schedule, owned by [scope]
 * (the application's own, cancelled on exit), so the timers cannot outlive the app.
 *
 * - **Never stacks.** [arm] replaces: the earlier timer for the id is cancelled under the same lock
 *   that registers the new one, so at most one timer per id exists.
 * - **Revertible.** [disarm] cancels exactly that timer; cancelling [scope] cancels all of them.
 * - **The fire is not the timer.** When the time comes the timer unregisters itself and launches the
 *   fire as a separate job. So `disarm(id)` or `arm(id, ...)` called from inside the fire (cancel of
 *   a running capture, an early-wake re-arm) can never cancel the caller of `fire`.
 * - **Clock changes** are answered by [RecordingAlarmTiming]: chunked sleeps, wall clock re-read.
 */
internal class DesktopRecordingAlarms(
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val onFire: suspend (scheduleId: String) -> Unit,
) : RecordingAlarms {
    private class Armed(val atMs: Long) {
        lateinit var job: Job
    }

    private val timers = HashMap<String, Armed>()

    /** How many timers are live. For tests. */
    internal fun armedCount(): Int = synchronized(timers) { timers.size }

    override fun exactAllowed(): Boolean = true

    override fun arm(scheduleId: String, atMs: Long): ArmKind {
        synchronized(timers) {
            timers.remove(scheduleId)?.job?.cancel()
            val armed = Armed(atMs)
            armed.job = scope.launch(start = CoroutineStart.LAZY) { wait(scheduleId, armed) }
            timers[scheduleId] = armed
            armed.job.start()
        }
        return ArmKind.EXACT
    }

    override fun disarm(scheduleId: String) {
        synchronized(timers) { timers.remove(scheduleId)?.job?.cancel() }
    }

    private suspend fun wait(scheduleId: String, armed: Armed) {
        while (true) {
            when (val step = RecordingAlarmTiming.next(wallClock(), armed.atMs)) {
                RecordingAlarmTiming.Step.FireNow -> break
                is RecordingAlarmTiming.Step.Sleep -> delay(step.ms)
            }
        }
        val stillCurrent = synchronized(timers) {
            if (timers[scheduleId] === armed) {
                timers.remove(scheduleId)
                true
            } else {
                false
            }
        }
        if (!stillCurrent) return
        scope.launch {
            try {
                onFire(scheduleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.withTag("Recordings").e(e) { "alarm fire failed" }
            }
        }
    }
}
