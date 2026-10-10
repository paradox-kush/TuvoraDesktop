package com.nuvio.app.core.storage

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A key -> secret map kept in one owner-only (0600) file. Every value is sealed with AES-256-GCM
 * ([DesktopSecretCipher]) and bound to its key (plus [aadNamespace]) as additional authenticated data; the master
 * key is held by the OS secret store ([MasterKeyVault]) and only falls back to a 0600 key file when none is reachable.
 * Nothing readable ever touches disk, and the file is useless without the key.
 */
internal class SealedFileStore(
    private val file: Path,
    private val vault: MasterKeyVault,
    private val aadNamespace: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val lock = Any()

    fun read(key: String): String? = synchronized(lock) {
        val sealed = load()[key] ?: return@synchronized null
        val master = vault.key(createIfMissing = false) ?: return@synchronized null
        DesktopSecretCipher.open(master, aad(key), sealed)
    }

    fun write(key: String, value: String?): Unit = synchronized(lock) {
        val items = load().toMutableMap()
        if (value == null) {
            items.remove(key)
        } else {
            val master = vault.key(createIfMissing = true) ?: throw IOException("no protected storage is available for the sign-in")
            items[key] = DesktopSecretCipher.seal(master, aad(key), value)
        }
        if (items.isEmpty()) {
            Files.deleteIfExists(file)
        } else {
            writePrivate(file, json.encodeToString(serializer, items))
        }
        Unit
    }

    /** Deletes the file and the master key it was sealed under. */
    fun clearAll(): Unit = synchronized(lock) {
        runCatching { Files.deleteIfExists(file) }
        vault.forget()
    }

    private fun load(): Map<String, String> =
        runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrNull() ?: emptyMap()

    private fun aad(key: String) = "$aadNamespace:$key"
}
