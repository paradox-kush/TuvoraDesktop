package com.nuvio.app.features.servers

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.MasterKeyProvider
import com.nuvio.app.core.storage.MasterKeyVault
import com.nuvio.app.core.storage.SealedFileStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop upgrade from the released fork stack (Desktop v0.9.x): the legacy files are written the way v0.9.5 wrote them - a
 * properties file with `entries_<profile>` / `trust`, and `mediaserver-tokens.json` sealed with AES-256-GCM (AAD
 * `com.nuvio.media.mediaserver:<key>`) under the master key of the OLD vault namespace - using a test key provider, never the real
 * Keychain. The new store is an in-memory fake, so nothing touches the real app-data directory either.
 */
class LegacyMediaServerDesktopUpgradeTest {
    private val dir: Path = Files.createTempDirectory("tuvora-legacy-upgrade")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class MemoryProvider(var key: ByteArray? = null) : MasterKeyProvider {
        override val name = "memory"
        override fun load(): ByteArray? = key
        override fun store(key: ByteArray): Boolean { this.key = key; return true }
        override fun delete() { key = null }
    }

    private class FakeStore(val values: MutableMap<String, String> = mutableMapOf()) : ServerKeyValueStore {
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private val den = """{"key":"jellyfin|m-den|u-viewer","type":"jellyfin","machineId":"m-den","userId":"u-viewer","name":"Den","address":"https://192.168.1.20:8920","syncAddress":true,"enabled":true,"userName":"viewer","homeRows":["continue_watching"],"homeLibraries":{"lib-movies":"Movies"}}"""
    private val pins = """{"192.168.1.20:8920":"AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12"}"""

    private fun tokenStore(provider: MasterKeyProvider) = SealedFileStore(
        file = dir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE),
        vault = MasterKeyVault(listOf(provider)),
        aadNamespace = DesktopLegacyMediaServerSource.AAD_NAMESPACE,
    )

    /** What v0.9.5 left on disk. */
    private fun seedLegacy(provider: MemoryProvider, withToken: Boolean = true, withDeviceId: Boolean = true) {
        val prefs = DesktopStorage.Store(dir.resolve("${DesktopLegacyMediaServerSource.PREFERENCES_NAME}.properties"))
        prefs.putString("entries_0", """{"version":1,"entries":[$den]}""")
        prefs.putString("entries_2", """{"version":1,"entries":[]}""")
        prefs.putString("trust", pins)
        val tokens = tokenStore(provider)
        if (withToken) tokens.write("cred:jellyfin:m-den:u-viewer", """{"accessToken":"legacy-secret-token","userName":"viewer"}""")
        if (withDeviceId) tokens.write("device-id", "0123456789abcdef0123456789abcdef")
    }

    private fun source(provider: MemoryProvider) = DesktopLegacyMediaServerSource(
        preferences = { DesktopStorage.Store(dir.resolve("${DesktopLegacyMediaServerSource.PREFERENCES_NAME}.properties")) },
        tokens = { tokenStore(provider) },
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a sealed token store and prefs written the released way land in the new store with the token and pin`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val store = FakeStore()
        val result = LegacyMediaServerImport(source(provider), store).runOnce()
        assertTrue(result.completed)
        assertEquals(1, result.imported)
        assertEquals(0, result.needSignIn)
        val connection = json.decodeFromString(ListSerializer(ServerConnection.serializer()), store.values.getValue("profile.0.connections")).single()
        assertEquals("https://192.168.1.20:8920", connection.address)
        assertEquals("legacy-secret-token", store.values["credential.${connection.credentialRef}"])
        assertEquals("0123456789abcdef0123456789abcdef", store.values["device.id"], "the token stays valid: the device id it was issued for is kept")
        assertTrue(store.values.getValue("trust.pins").contains("192.168.1.20:8920"))
        assertTrue(source(provider).isMigrated())
    }

    @Test
    fun `the old sealed file is only read - it and its key survive`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val before = Files.readString(dir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE))
        LegacyMediaServerImport(source(provider), FakeStore()).runOnce()
        assertEquals(before, Files.readString(dir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE)))
        assertTrue(provider.key != null)
    }

    @Test
    fun `a token sealed under another key reads as absent and the server asks to sign in again`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        provider.key = ByteArray(32) { 7 } // the keyring now answers with a different key
        val store = FakeStore()
        val result = LegacyMediaServerImport(source(provider), store).runOnce()
        assertEquals(1, result.imported)
        assertEquals(1, result.needSignIn)
        assertNull(store.values["device.id"])
        assertFalse(store.values.keys.any { it.startsWith("credential.") })
    }

    @Test
    fun `a second launch duplicates nothing`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val store = FakeStore()
        LegacyMediaServerImport(source(provider), store).runOnce()
        val after = store.values.toMap()
        val again = LegacyMediaServerImport(source(provider), store).runOnce()
        assertEquals(0, again.imported)
        assertEquals(after, store.values.toMap())
    }

    @Test
    fun `a different device id already in the new store keeps the token out`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val store = FakeStore(mutableMapOf("device.id" to "tuvora-mobile-already"))
        val result = LegacyMediaServerImport(source(provider), store).runOnce()
        assertEquals(1, result.needSignIn)
        assertNotEquals("0123456789abcdef0123456789abcdef", store.values["device.id"])
    }

    @Test
    fun `the old namespace names are the released ones`() {
        assertEquals("mediaserver", DesktopLegacyMediaServerSource.VAULT_NAMESPACE)
        assertEquals("com.nuvio.media.mediaserver", DesktopLegacyMediaServerSource.AAD_NAMESPACE)
        assertEquals("mediaserver-tokens.json", DesktopLegacyMediaServerSource.TOKENS_FILE)
        assertEquals("nuvio_mediaserver", DesktopLegacyMediaServerSource.PREFERENCES_NAME)
    }

    @Test
    fun `a disabled source (in-memory test mode) reads and erases nothing`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val off = DesktopLegacyMediaServerSource(
            preferences = { DesktopStorage.Store(dir.resolve("${DesktopLegacyMediaServerSource.PREFERENCES_NAME}.properties")) },
            tokens = { tokenStore(provider) },
            enabled = { false },
        )
        assertFalse(off.isAvailable())
        assertTrue(off.entryProfileIds().isEmpty())
        off.eraseAll()
        assertTrue(Files.exists(dir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE)))
    }

    @Test
    fun `erase forgets the legacy values and keeps the migrated marker`() {
        val provider = MemoryProvider()
        seedLegacy(provider)
        val src = source(provider)
        src.eraseAll()
        assertTrue(src.entryProfileIds().isEmpty())
        assertTrue(src.isMigrated())
        assertFalse(Files.exists(dir.resolve(DesktopLegacyMediaServerSource.TOKENS_FILE)))
        assertNull(provider.key, "the old master key goes with the account wipe")
    }
}
