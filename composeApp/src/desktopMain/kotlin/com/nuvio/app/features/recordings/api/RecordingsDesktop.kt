package com.nuvio.app.features.recordings.api

import com.nuvio.app.DesktopAppScope
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.recordings.internal.DesktopRecordingAlarms
import com.nuvio.app.features.recordings.internal.DesktopRecordingFiles
import com.nuvio.app.features.recordings.internal.DesktopRecordingSaver
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import java.io.File
import kotlinx.coroutines.launch

/** Desktop capabilities. One constant per platform, so wave 4 (live capture) flips it in one place. */
private object DesktopRecordingCapabilities {
    /** TODO(live capture pending live-pause branch): wave 4 sets this true. */
    const val CAN_RECORD_LIVE = false
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true)
}

/**
 * The desktop composition of the recordings feature. A desktop records while the app is open, so
 * the alarms are in-app timers on the application's own scope ([DesktopAppScope], cancelled when
 * the window closes) and nothing here outlives the process.
 *
 * [install] builds the one repository and publishes it (no I/O, safe to call once at process init);
 * [onAppStart] then loads state, recovers a run a dead process left behind, re-arms, and fires what
 * is overdue, all off the main thread.
 */
object RecordingsDesktop {
    @Volatile
    private var chosenFolder: File? = null

    /** The one seam for "where recordings go". Null (the default) is the app's own folder. The Settings wave calls this. */
    fun setRecordingsFolder(folder: File?) {
        chosenFolder = folder
    }

    private fun recordingsDir(): File =
        chosenFolder ?: DesktopStorage.rootDir.resolve("recordings").toFile()

    @Synchronized
    fun install() {
        if (RecordingsAccess.current() != null) return
        val files = DesktopRecordingFiles(
            stateDirOf = { DesktopStorage.rootDir.resolve("recordings-state").toFile() },
            recordingsDir = ::recordingsDir,
        )
        val alarms = DesktopRecordingAlarms(
            scope = DesktopAppScope.scope,
            onFire = { id -> RecordingsAccess.current()?.fire(id, System.currentTimeMillis()) },
        )
        RecordingsAccess.register(
            RecordingsRepository(
                files = files,
                alarms = alarms,
                saver = DesktopRecordingSaver(),
                capabilities = DesktopRecordingCapabilities.value,
                lowSpacePolicy = { LowSpacePolicy.STOP }, // TODO(wave 3): read the user's setting
            ),
        )
    }

    /** Idempotent: every call re-reads nothing twice (the repository's own start guards that). */
    fun onAppStart() {
        install()
        val port = RecordingsAccess.current() ?: return
        DesktopAppScope.scope.launch {
            val now = System.currentTimeMillis()
            port.start(now)
            port.rearmAll(now)
            port.sweepOverdue(now)
        }
    }
}
