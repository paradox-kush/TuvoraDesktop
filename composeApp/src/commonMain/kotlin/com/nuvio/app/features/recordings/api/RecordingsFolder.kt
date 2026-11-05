package com.nuvio.app.features.recordings.api

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.StateFlow

/** What happened when the user asked to change the folder new recordings are saved into. */
sealed interface FolderChangeResult {
    /** The user closed the chooser without picking anything. */
    data object Cancelled : FolderChangeResult

    /** The folder was accepted, remembered, and new recordings go there from now on. */
    data class Changed(val path: String) : FolderChangeResult

    /** The picked folder cannot be written to, so nothing changed. */
    data class NotWritable(val path: String) : FolderChangeResult
}

/**
 * The "where new recordings go" setting, for platforms where the user owns that choice (Desktop).
 * Null in [LocalRecordingsFolder] means this platform has no such choice and the page shows nothing for it.
 */
interface RecordingsFolderControl {
    /** The folder new recordings are saved into right now. */
    val folderPath: StateFlow<String>

    /** Opens the platform's folder chooser, validates the pick, and applies it. Safe to call from any dispatcher. */
    suspend fun chooseNewFolder(): FolderChangeResult
}

val LocalRecordingsFolder = staticCompositionLocalOf<RecordingsFolderControl?> { null }

/** Where the platform composition root publishes its [RecordingsFolderControl], if it has one. */
object RecordingsFolderAccess {
    private val control = atomic<RecordingsFolderControl?>(null)
    fun register(value: RecordingsFolderControl) { control.value = value }
    fun current(): RecordingsFolderControl? = control.value
}
