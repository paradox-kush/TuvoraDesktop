package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.FolderChangeResult
import com.nuvio.app.features.recordings.api.RecordingsFolderControl
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Folder checks. The only I/O is on the folder being judged. */
internal object RecordingsFolderPolicy {
    /** True when [dir] is (or can be made into) a directory this process can create files in. */
    fun isWritable(dir: File): Boolean {
        if (dir.exists() && !dir.isDirectory) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        if (!dir.canWrite()) return false
        // The permission bits can lie (read-only volumes, ACLs): the honest test is to create a file.
        val probe = File(dir, ".tuvora-write-test-${UUID.randomUUID()}")
        return try {
            probe.createNewFile().also { probe.delete() }
        } catch (_: Exception) {
            false
        }
    }
}

/** Where the folder choice is remembered. Desktop backs it with its properties store; tests with a map. */
internal interface RecordingsFolderStore {
    fun chosen(): String?
    fun setChosen(path: String?)

    /** Folders recordings were saved to before, so older recordings are still found after a change. */
    fun earlier(): List<String>
    fun setEarlier(paths: List<String>)
}

/**
 * The user's "new recordings go here" choice. Changing it affects only new recordings; the folders used
 * before are remembered so existing recordings keep playing from where they were saved.
 */
internal class RecordingsFolderSetting(
    private val store: RecordingsFolderStore,
    private val defaultDir: File,
    private val isWritable: (File) -> Boolean = RecordingsFolderPolicy::isWritable,
    private val pick: suspend () -> File?,
    private val onApplied: (File?) -> Unit = {},
) : RecordingsFolderControl {
    private val path = MutableStateFlow(defaultDir.path)
    override val folderPath: StateFlow<String> = path

    /** The folder new recordings go into: the remembered choice, or the default when none/unusable. */
    fun currentDir(): File = store.chosen()?.let(::File)?.takeIf { it.isDirectory } ?: defaultDir

    /** Folders that may hold older recordings (not the current one). */
    fun earlierDirs(): List<File> = store.earlier().map(::File)

    /** Applies the remembered choice at startup. A remembered folder that vanished falls back to the default. */
    fun restore() {
        val dir = currentDir()
        path.value = dir.path
        onApplied(dir.takeIf { it != defaultDir })
    }

    override suspend fun chooseNewFolder(): FolderChangeResult {
        val picked = pick() ?: return FolderChangeResult.Cancelled
        return apply(picked)
    }

    internal suspend fun apply(dir: File): FolderChangeResult {
        val usable = withContext(Dispatchers.IO) { isWritable(dir) }
        if (!usable) return FolderChangeResult.NotWritable(dir.path)
        val previous = currentDir()
        if (previous != dir) {
            store.setEarlier((listOf(previous.path) + store.earlier()).filterNot { it == dir.path }.distinct())
        }
        store.setChosen(dir.takeIf { it != defaultDir }?.path)
        path.value = dir.path
        onApplied(dir.takeIf { it != defaultDir })
        return FolderChangeResult.Changed(dir.path)
    }
}
