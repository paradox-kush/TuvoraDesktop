package com.nuvio.app.features.mediaserver.internal.store

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.MasterKeyVault
import com.nuvio.app.core.storage.SealedFileStore
import java.nio.file.Path

/**
 * The desktop token store: values are sealed with AES-256-GCM ([SealedFileStore]) in an owner-only (0600)
 * file; the master key is held by the OS secret store ([MasterKeyVault]) and only falls back to a 0600 key file
 * when none is reachable (named gap). Nothing readable ever touches disk, and the file is useless without the key.
 */
internal class DesktopSecureTokenStore(
    file: Path,
    vault: MasterKeyVault,
) : SecureTokenStore {
    private val sealed = SealedFileStore(file, vault, aadNamespace = "com.nuvio.media.mediaserver")

    override fun read(key: String): String? = sealed.read(key)

    override fun write(key: String, value: String?) = sealed.write(key, value)

    override fun clearAll() = sealed.clearAll()
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
