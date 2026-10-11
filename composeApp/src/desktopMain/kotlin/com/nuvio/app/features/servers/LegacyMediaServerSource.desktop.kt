package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.MasterKeyVault
import com.nuvio.app.core.storage.SealedFileStore

/**
 * Read-only access to what the released desktop fork stack (features/mediaserver, Desktop v0.9.x) stored:
 *  - `nuvio_mediaserver.properties` in the app-data directory: `entries_<profileId>` and `trust` (plain JSON, like every desktop preference);
 *  - `mediaserver-tokens.json`: AES-256-GCM items (`cred:...`, `device-id`) bound to `com.nuvio.media.mediaserver:<key>` as AAD, sealed under a
 *    master key held by the OS secret store under the OLD namespace "mediaserver" (Keychain service `com.nuvio.media.mediaserver`, DPAPI blob
 *    `mediaserver-master-key.dpapi`, key file `mediaserver-master.key`). Upstream's servers storage uses the namespace "servers", so the two
 *    never share a key: this reader opens the old store with the old key, and the importer re-seals into the new one through [ServerStorage].
 * Nothing here ever creates a key or removes a value on a failed read (the fork's own store did; the importer must not).
 */
internal class DesktopLegacyMediaServerSource(
    private val preferences: () -> DesktopStorage.Store,
    private val tokens: () -> SealedFileStore,
    private val enabled: () -> Boolean = { true },
) : LegacyMediaServerSource {
    override fun isAvailable(): Boolean = enabled()

    override fun entryProfileIds(): List<Int> {
        if (!enabled()) return emptyList()
        return preferences().keys().filter { it.startsWith(ENTRIES_PREFIX) }.mapNotNull { it.removePrefix(ENTRIES_PREFIX).toIntOrNull() }.sorted()
    }

    override fun entriesJson(profileId: Int): String? = if (enabled()) preferences().getString("$ENTRIES_PREFIX$profileId") else null

    override fun trustJson(): String? = if (enabled()) preferences().getString(TRUST_KEY) else null

    override fun secret(key: String): String? = if (enabled()) runCatching { tokens().read(key) }.getOrNull() else null

    override fun isMigrated(): Boolean = enabled() && preferences().getBoolean(MIGRATED_KEY) == true

    override fun markMigrated() {
        if (enabled()) preferences().putBoolean(MIGRATED_KEY, true)
    }

    override fun eraseAll() {
        if (!enabled()) return
        val store = preferences()
        store.removeAll(store.keys())
        store.putBoolean(MIGRATED_KEY, true)
        runCatching { tokens().clearAll() }
    }

    companion object {
        const val PREFERENCES_NAME = "nuvio_mediaserver"
        const val TOKENS_FILE = "mediaserver-tokens.json"
        const val VAULT_NAMESPACE = "mediaserver"
        const val AAD_NAMESPACE = "com.nuvio.media.mediaserver"
        private const val ENTRIES_PREFIX = "entries_"
        private const val TRUST_KEY = "trust"
        private const val MIGRATED_KEY = "migrated_to_servers_v1"
    }
}

/** The tests run with in-memory server storage and must never read the real app-data directory or the OS secret store: the import is off then. */
internal actual object PlatformLegacyMediaServerSource : LegacyMediaServerSource by DesktopLegacyMediaServerSource(
    preferences = { DesktopStorage.store(DesktopLegacyMediaServerSource.PREFERENCES_NAME) },
    tokens = { legacyTokens },
    enabled = { System.getProperty(ServerStorage.IN_MEMORY_PROPERTY) != "true" },
)

private val legacyTokens: SealedFileStore by lazy {
    SealedFileStore(
        file = DesktopStorage.rootDir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE),
        vault = MasterKeyVault.forThisMachine(DesktopStorage.rootDir, namespace = DesktopLegacyMediaServerSource.VAULT_NAMESPACE),
        aadNamespace = DesktopLegacyMediaServerSource.AAD_NAMESPACE,
    )
}
