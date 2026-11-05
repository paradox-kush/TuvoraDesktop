package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.RecordingFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop storage: state files in [stateDir], recordings in whatever [recordingsDir] answers now
 * (the app's own folder today; the Settings wave swaps in the user's choice through one setter, so
 * it is read on every use). A state write goes to a temporary file, is fsynced, then moved over the
 * old one, so a crash leaves the old state or the new one and never half of each.
 */
internal class DesktopRecordingFiles(
    private val stateDir: File,
    private val recordingsDir: () -> File,
) : RecordingFiles {

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(stateDir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        check(stateDir.isDirectory || stateDir.mkdirs()) { "Cannot create recordings state directory" }
        val tmp = File(stateDir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        val target = File(stateDir, name)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    override suspend fun keepAside(name: String) = withContext(Dispatchers.IO) {
        val f = File(stateDir, name)
        if (f.exists()) f.renameTo(File(stateDir, "$name.corrupt-${System.currentTimeMillis()}"))
        Unit
    }

    override fun pathFor(fileName: String): String {
        require(File(fileName).name == fileName && fileName.isNotBlank()) { "not a plain file name" }
        return File(recordingsDir(), fileName).path
    }

    override fun freeBytes(): Long {
        val dir = recordingsDir()
        dir.mkdirs()
        return dir.usableSpace
    }

    override fun sizeOf(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }
}
