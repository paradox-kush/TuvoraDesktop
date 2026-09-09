package com.nuvio.app.core.journal

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * File-backed journal in the desktop app-data directory. Written to a temp file, fsync'd
 * (FileChannel.force), then atomically moved into place, and finally VERIFIED by read-back — a
 * write call returning is not proof of durable persistence. Self-resolving (no init needed): the
 * app-data dir is available from process start.
 */
internal actual object StartupJournalStore {
    private const val FILE = "startup-journal.json"
    private val path get() = DesktopStorage.rootDir.resolve(FILE)

    actual fun read(): String? = runCatching {
        if (!Files.exists(path)) return@runCatching null
        // Bound the read: a corrupt/tampered journal must not fully load into memory.
        val text = readBoundedJournal(Files.newInputStream(path), StartupJournalPolicy.MAX_BLOB_BYTES)
        if (text == null) Files.deleteIfExists(path)
        text
    }.getOrNull()

    actual fun writeVerified(content: String): Boolean = runCatching {
        Files.createDirectories(path.parent)
        val tmp = DesktopStorage.rootDir.resolve("$FILE.tmp")
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { ch ->
            ch.write(ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
            ch.force(true)
        }
        runCatching {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            // A filesystem that cannot atomic-move over an existing file — fall back to a plain
            // replace. The read-back below is the actual durability proof either way.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.readString(path) == content
    }.getOrDefault(false)
}

internal actual fun journalNowMs(): Long = System.currentTimeMillis()

/** Reads up to [maxBytes] of UTF-8, returning null if the source exceeds it — enforced while
 *  consuming, so an oversized/corrupt file never fully lands in memory. */
private fun readBoundedJournal(input: java.io.InputStream, maxBytes: Int): String? {
    input.use { ins ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
