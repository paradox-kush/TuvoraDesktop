package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.ArmKind
import com.nuvio.app.features.recordings.api.RecordingAlarms
import kotlinx.coroutines.CoroutineScope

/** STUB (red commit): behaviour arrives in a later commit. */
internal class DesktopRecordingAlarms(
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val onFire: suspend (scheduleId: String) -> Unit,
) : RecordingAlarms {
    internal fun armedCount(): Int = 0
    override fun exactAllowed(): Boolean = false
    override fun arm(scheduleId: String, atMs: Long): ArmKind = ArmKind.NONE
    override fun disarm(scheduleId: String) = Unit
}
