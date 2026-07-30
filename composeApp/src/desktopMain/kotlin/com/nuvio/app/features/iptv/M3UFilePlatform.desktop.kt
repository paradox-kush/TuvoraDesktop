package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Desktop file-source platform. The picker is the native AWT [FileDialog] (native open panel on
 * macOS/Windows); the picked file's bytes are copied under `<app-data>/playlists/{id}.m3u`, and the
 * ingest streams that local copy — mirroring the Android ACTION_OPEN_DOCUMENT implementation.
 */
private fun playlistsDir(): File {
    val dir = DesktopStorage.rootDir.resolve("playlists")
    Files.createDirectories(dir)
    return dir.toFile()
}

/** Playlist ids contain '://', ':', '|' — flatten to a filesystem-safe stable basename. */
private fun safeName(playlistId: String): String = buildString(playlistId.length) {
    for (c in playlistId) append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
}

actual fun pickM3UFile(onPicked: (PickedM3UFile?) -> Unit) {
    EventQueue.invokeLater {
        val picked: File? = runCatching {
            val dialog = FileDialog(null as Frame?, "Choose an M3U playlist", FileDialog.LOAD)
            // Native filters are best-effort: providers use inconsistent MIME/extensions, so
            // accept anything that looks like a playlist or plain text (matches Android's picker).
            dialog.setFilenameFilter { _, name ->
                val n = name.lowercase()
                n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".txt") || n.endsWith(".gz")
            }
            dialog.isVisible = true
            val file = dialog.file?.let { File(dialog.directory ?: "", it) }
            file?.takeIf { it.isFile }
        }.getOrNull()

        if (picked == null) {
            onPicked(null)
        } else {
            onPicked(
                PickedM3UFile(
                    fileName = picked.name,
                    readBytes = { withContext(Dispatchers.IO) { picked.readBytes() } },
                ),
            )
        }
    }
}

actual suspend fun copyM3UFileToStorage(playlistId: String, picked: PickedM3UFile): String =
    withContext(Dispatchers.IO) {
        val bytes = picked.readBytes()
        val dest = File(playlistsDir(), "${safeName(playlistId)}.m3u")
        dest.writeBytes(bytes)
        dest.absolutePath
    }

actual fun m3uFileStoragePath(playlistId: String): String =
    File(playlistsDir(), "${safeName(playlistId)}.m3u").absolutePath

actual fun fileExists(path: String): Boolean = File(path).exists()

actual fun deleteM3UFile(playlistId: String) {
    runCatching { File(playlistsDir(), "${safeName(playlistId)}.m3u").delete() }
}

actual suspend fun streamFileLines(path: String, onLine: (String) -> Unit): Unit =
    withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) error("Playlist file not found: $path")
        // Sniff the gzip magic (0x1f 0x8b) via a 2-byte pushback so a saved .m3u.gz streams decompressed.
        val pushback = PushbackInputStream(file.inputStream().buffered(), 2)
        val b0 = pushback.read()
        val b1 = pushback.read()
        if (b1 != -1) pushback.unread(b1)
        if (b0 != -1) pushback.unread(b0)
        val gzipped = b0 == 0x1f && b1 == 0x8b
        val stream = if (gzipped) GZIPInputStream(pushback) else pushback
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
    }
