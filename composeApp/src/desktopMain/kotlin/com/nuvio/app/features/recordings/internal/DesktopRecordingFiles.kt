package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.RecordingFiles
import java.io.File

/** STUB (red commit): behaviour arrives in a later commit. */
internal class DesktopRecordingFiles(
    private val stateDir: File,
    private val recordingsDir: () -> File,
) : RecordingFiles {
    override suspend fun read(name: String): String? = TODO()
    override suspend fun write(name: String, text: String) = TODO()
    override suspend fun keepAside(name: String) = TODO()
    override fun pathFor(fileName: String): String = TODO()
    override fun freeBytes(): Long = TODO()
    override fun sizeOf(path: String): Long = TODO()
    override suspend fun delete(path: String) = TODO()
}
