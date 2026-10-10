package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.MasterKeyVault
import com.nuvio.app.core.storage.SealedFileStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork decision (13th sync): upstream's desktop actual kept the server sign-in tokens in a plain
 * `.properties` file. Android seals them with the Keystore and iOS uses the Keychain, so desktop is the odd one
 * out: here every value is AES-256-GCM sealed in an owner-only file, with the master key held by the OS secret
 * store (macOS Keychain, libsecret, DPAPI; a 0600 key file only when none answers). The key has its own
 * namespace, so clearing the servers never touches another store's key.
 *
 * Tests run with [IN_MEMORY_PROPERTY] set (build.gradle.kts): they must not create keychain items or write
 * into the real app-data directory. The real path is covered by SealedFileStoreTest.
 */
internal actual object ServerStorage {
    const val IN_MEMORY_PROPERTY = "tuvora.servers.inMemoryStorage"

    private val memory = ConcurrentHashMap<String, String>()
    private val inMemory get() = System.getProperty(IN_MEMORY_PROPERTY) == "true"

    private val sealed: SealedFileStore by lazy {
        SealedFileStore(
            file = DesktopStorage.rootDir.resolve("server-credentials.json"),
            vault = MasterKeyVault.forThisMachine(DesktopStorage.rootDir, namespace = "servers"),
            aadNamespace = "com.nuvio.media.servers.credentials.v1",
        )
    }

    actual fun read(key: String): String? = if (inMemory) memory[key] else sealed.read(key)

    actual fun write(key: String, value: String?) {
        if (!inMemory) return sealed.write(key, value)
        if (value == null) memory.remove(key) else memory[key] = value
    }

    actual fun clear() {
        if (inMemory) memory.clear() else sealed.clearAll()
    }
}
