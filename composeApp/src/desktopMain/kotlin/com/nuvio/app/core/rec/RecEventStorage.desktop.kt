package com.nuvio.app.core.rec

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.util.Locale

private const val QUEUE_FILE = "rec-events-queue.jsonl"

internal actual object RecEventStorage {
    private val store = DesktopStorage.store("nuvio_rec_events")

    actual fun loadString(key: String): String? = store.getString(key)

    actual fun saveString(key: String, value: String) {
        store.putString(key, value)
    }

    actual fun removeString(key: String) {
        store.remove(key)
    }

    actual fun loadBoolean(key: String, default: Boolean): Boolean =
        store.getBoolean(key) ?: default

    actual fun saveBoolean(key: String, value: Boolean) {
        store.putBoolean(key, value)
    }

    // DesktopStorage has no long accessor; the queue's only long is a timestamp, so round-trip it
    // as a string rather than widening the shared store's API for one caller.
    actual fun loadLong(key: String, default: Long): Long =
        store.getString(key)?.toLongOrNull() ?: default

    actual fun saveLong(key: String, value: Long) {
        store.putString(key, value.toString())
    }

    actual fun loadQueue(): String? = runCatching {
        val path = DesktopStorage.rootDir.resolve(QUEUE_FILE)
        if (!Files.exists(path)) return@runCatching null
        // Enforce the byte limit WHILE consuming the stream (not via a length pre-check, which races a
        // growing file and can't bound a length-unknown source). A file over MAX_QUEUE_BYTES is not a
        // legitimate queue — drop it and return nothing so it can't linger and re-OOM next launch.
        val text = readBoundedUtf8(Files.newInputStream(path), RecEventQueueRestorePolicy.MAX_QUEUE_BYTES)
        if (text == null) Files.deleteIfExists(path)
        text
    }.getOrNull()

    actual fun saveQueue(contents: String?) {
        runCatching {
            val path = DesktopStorage.rootDir.resolve(QUEUE_FILE)
            if (contents == null) {
                Files.deleteIfExists(path)
            } else {
                Files.createDirectories(path.parent)
                Files.writeString(path, contents)
            }
        }
    }
}

/**
 * One codebase, three values — the desktop build ships to all three OSes and they differ enough
 * in input model and session length that pooling them would hide what training wants to see.
 */
internal actual val recAppIdentifier: String = run {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
    when {
        os.contains("mac") || os.contains("darwin") -> "desktop-mac"
        os.contains("win") -> "desktop-windows"
        else -> "desktop-linux"
    }
}

internal actual fun recNowMillis(): Long = System.currentTimeMillis()

/**
 * Reads up to [maxBytes] of UTF-8 from [input], returning null if the content exceeds [maxBytes].
 * The limit is checked as the stream is consumed, so an oversized or growing source is rejected
 * before it fully lands in memory. Closes [input]. Testable with any InputStream.
 */
internal fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Int): String? {
    input.use { ins ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        val cap = maxBytes.toLong()
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}
