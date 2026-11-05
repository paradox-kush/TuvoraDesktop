package com.nuvio.app.features.recordings.api

import com.nuvio.app.DesktopAppScope
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.recordings.internal.DesktopRecordingAlarms
import com.nuvio.app.features.recordings.internal.DesktopRecordingFiles
import com.nuvio.app.features.recordings.internal.DesktopRecordingSaver
import com.nuvio.app.features.recordings.internal.RecordingsFolderSetting
import com.nuvio.app.features.recordings.internal.RecordingsFolderStore
import com.nuvio.app.features.recordings.internal.RecordingsRepository
import com.nuvio.app.features.recordings.internal.pickRecordingsFolder
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

    /** The one seam for "where recordings go". Null (the default) is the app's own folder. The folder picker on the Recordings page calls it through [RecordingsFolderSetting]. */
    fun setRecordingsFolder(folder: File?) {
        chosenFolder = folder
    }

    private fun recordingsDir(): File =
        chosenFolder ?: DesktopStorage.rootDir.resolve("recordings").toFile()

    /** Remembered in the app's properties store, like the other desktop settings. */
    private object FolderStore : RecordingsFolderStore {
        private val store get() = DesktopStorage.store("tuvora_recordings")
        override fun chosen(): String? = store.getString("folder")?.takeIf { it.isNotBlank() }
        override fun setChosen(path: String?) = store.putString("folder", path)
        override fun earlier(): List<String> =
            store.getString("earlier_folders").orEmpty().split('\n').filter { it.isNotBlank() }
        override fun setEarlier(paths: List<String>) =
            store.putString("earlier_folders", paths.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    private val folderSetting by lazy {
        RecordingsFolderSetting(
            store = FolderStore,
            defaultDir = DesktopStorage.rootDir.resolve("recordings").toFile(),
            pick = ::pickRecordingsFolder,
            onApplied = ::setRecordingsFolder,
        )
    }

    @Synchronized
    fun install() {
        if (RecordingsAccess.current() != null) return
        folderSetting.restore()
        RecordingsFolderAccess.register(folderSetting)
        val files = DesktopRecordingFiles(
            stateDirOf = { DesktopStorage.rootDir.resolve("recordings-state").toFile() },
            recordingsDir = ::recordingsDir,
            earlierDirs = folderSetting::earlierDirs,
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
