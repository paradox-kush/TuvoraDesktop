package com.nuvio.app.features.mediaserver.internal.store

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop token store: values are sealed with AES-256-GCM ([DesktopSecretCipher]) in an owner-only (0600)
 * file; the master key is held by the OS secret store ([MasterKeyVault]) and only falls back to a 0600 key file
 * when none is reachable (named gap). Nothing readable ever touches disk, and the file is useless without the key.
 */
internal class DesktopSecureTokenStore(
    private val file: Path,
    private val vault: MasterKeyVault,
) : SecureTokenStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val lock = Any()

    override fun read(key: String): String? = synchronized(lock) {
        val sealed = load()[key] ?: return@synchronized null
        val master = vault.key(createIfMissing = false) ?: return@synchronized null
        DesktopSecretCipher.open(master, aad(key), sealed)
    }

    override fun write(key: String, value: String?): Unit = synchronized(lock) {
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

    override fun clearAll(): Unit = synchronized(lock) {
        runCatching { Files.deleteIfExists(file) }
        vault.forget()
    }

    private fun load(): Map<String, String> =
        runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrNull() ?: emptyMap()

    private fun aad(key: String) = "com.nuvio.media.mediaserver:$key"
}

internal actual object PlatformSecureTokenStore : SecureTokenStore {
    private val delegate: DesktopSecureTokenStore by lazy {
        DesktopSecureTokenStore(
            file = DesktopStorage.rootDir.resolve("mediaserver-tokens.json"),
            vault = MasterKeyVault.forThisMachine(DesktopStorage.rootDir),
        )
    }

    actual override fun read(key: String): String? = delegate.read(key)
    actual override fun write(key: String, value: String?) { delegate.write(key, value) }
    actual override fun clearAll() { delegate.clearAll() }
}
