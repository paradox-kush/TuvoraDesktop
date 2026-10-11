package com.nuvio.app.features.servers

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are what v1.13.5 (and every release since v1.13.0, the first with the feature) wrote: `EntriesBlob(version = 1, entries)` with
 * `Json { ignoreUnknownKeys = true; encodeDefaults = true }` - properties in declaration order, enums by @SerialName, the home-row Set as an
 * array, the library map as an object - and `StoredCredential` with the default Json (a null userName is omitted).
 */
class LegacyMediaServerImportTest {
    private val den = """{"key":"jellyfin|m-den|u-viewer","type":"jellyfin","machineId":"m-den","userId":"u-viewer","name":"Den","address":"https://jf.example.com/jellyfin","syncAddress":true,"enabled":true,"userName":"viewer","homeRows":["continue_watching","recently_added"],"homeLibraries":{"lib-movies":"Movies"}}"""
    private val attic = """{"key":"emby|m-attic|u-kid","type":"emby","machineId":"m-attic","userId":"u-kid","name":"Attic","address":"http://192.168.1.20:8096","syncAddress":false,"enabled":false,"userName":"kid","homeRows":[],"homeLibraries":{}}"""
    private val syncedNoAddress = """{"key":"jellyfin|m-nas|u-me","type":"jellyfin","machineId":"m-nas","userId":"u-me","name":"NAS","syncAddress":true,"enabled":true,"homeRows":[],"homeLibraries":{}}"""
    private fun blob(vararg entries: String) = """{"version":1,"entries":[${entries.joinToString(",")}]}"""
    private fun credential(token: String, user: String? = null) =
        if (user == null) """{"accessToken":"$token"}""" else """{"accessToken":"$token","userName":"$user"}"""
    private val pins = """{"192.168.1.20:8920":"AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12"}"""

    private class FakeSource(
        val entries: Map<Int, String> = emptyMap(),
        val trust: String? = null,
        val secrets: Map<String, String> = emptyMap(),
        var migrated: Boolean = false,
        var available: Boolean = true,
    ) : LegacyMediaServerSource {
        var erased = false
        override fun isAvailable() = available
        override fun entryProfileIds() = entries.keys.sorted()
        override fun entriesJson(profileId: Int) = entries[profileId]
        override fun trustJson() = trust
        override fun secret(key: String) = secrets[key]
        override fun isMigrated() = migrated
        override fun markMigrated() { migrated = true }
        override fun eraseAll() { erased = true }
    }

    private class FakeStore(val values: MutableMap<String, String> = mutableMapOf()) : ServerKeyValueStore {
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }

    private var counter = 0
    private fun importer(source: LegacyMediaServerSource, store: ServerKeyValueStore) =
        LegacyMediaServerImport(source, store, newId = { prefix -> prefix + (counter++).toString().padStart(3, '0') })

    private val json = Json { ignoreUnknownKeys = true }
    private fun connections(store: FakeStore, profile: Int) =
        store.values["profile.$profile.connections"]?.let { json.decodeFromString(ListSerializer(ServerConnection.serializer()), it) }.orEmpty()

    // --- parsing (real fixtures) ---

    @Test
    fun `the released entries blob parses with every field`() {
        val parsed = LegacyMediaServerFormat.parseEntries(blob(den, attic, syncedNoAddress))
        assertEquals(0, parsed.unreadable)
        assertEquals(listOf("Den", "Attic", "NAS"), parsed.entries.map { it.name })
        val first = parsed.entries[0]
        assertEquals("jellyfin", first.type)
        assertEquals("m-den", first.machineId)
        assertEquals("u-viewer", first.userId)
        assertEquals("https://jf.example.com/jellyfin", first.address)
        assertEquals("viewer", first.userName)
        assertEquals(setOf("continue_watching", "recently_added"), first.homeRows)
        assertEquals(mapOf("lib-movies" to "Movies"), first.homeLibraries)
        assertEquals("jellyfin:m-den:u-viewer", first.serverKey)
        assertFalse(parsed.entries[1].enabled, "the disabled flag")
        assertNull(parsed.entries[2].address, "a server that arrived from another device has no address")
    }

    @Test
    fun `an unreadable entry is counted and the others survive`() {
        val parsed = LegacyMediaServerFormat.parseEntries("""{"version":1,"entries":[$den,{"key":"broken"},{"type":"plex","machineId":"x","userId":"y"}]}""")
        assertEquals(listOf("Den"), parsed.entries.map { it.name })
        assertEquals(2, parsed.unreadable)
        assertEquals(1, LegacyMediaServerFormat.parseEntries("not json").unreadable)
        assertEquals(0, LegacyMediaServerFormat.parseEntries(null).unreadable)
    }

    @Test
    fun `credentials and pins parse`() {
        assertEquals("tok", LegacyMediaServerFormat.parseToken(credential("tok", "viewer")))
        assertEquals("tok", LegacyMediaServerFormat.parseToken(credential("tok")))
        assertNull(LegacyMediaServerFormat.parseToken("""{"accessToken":""}"""))
        assertNull(LegacyMediaServerFormat.parseToken("{not json"))
        assertEquals(1, LegacyMediaServerFormat.parsePins(pins).size)
        assertTrue(LegacyMediaServerFormat.parsePins("garbage").isEmpty())
    }

    // --- mapping ---

    @Test
    fun `a legacy entry maps onto upstream's connection`() {
        val entry = LegacyMediaServerFormat.parseEntries(blob(den)).entries.single()
        val connection = LegacyMediaServerMapper.connectionFor(entry, id = "c1", credentialRef = "k1")
        assertEquals("jellyfin", connection.providerId)
        assertEquals("Den", connection.name)
        assertEquals("https://jf.example.com/jellyfin", connection.address)
        assertEquals("m-den", connection.remoteServerId)
        assertEquals("u-viewer", connection.remoteUserId)
        assertEquals("viewer", connection.userName)
        assertTrue(connection.enabled)
        assertTrue(connection.importWatchState, "Continue watching on Home maps to importing watch progress")
        assertEquals(listOf("lib-movies"), connection.libraries.map { it.id })
        assertTrue(connection.libraries.single().selected)
    }

    @Test
    fun `a server without an address is kept with an empty one and the disabled flag survives`() {
        val parsed = LegacyMediaServerFormat.parseEntries(blob(attic, syncedNoAddress)).entries
        assertFalse(LegacyMediaServerMapper.connectionFor(parsed[0], "c", "k").enabled)
        assertFalse(LegacyMediaServerMapper.connectionFor(parsed[0], "c", "k").importWatchState)
        assertEquals("", LegacyMediaServerMapper.connectionFor(parsed[1], "c", "k").address)
    }

    @Test
    fun `a token is carried only for the device identity it was issued to`() {
        assertTrue(LegacyMediaServerMapper.canCarryToken("t", "dev", null))
        assertTrue(LegacyMediaServerMapper.canCarryToken("t", "dev", "dev"))
        assertFalse(LegacyMediaServerMapper.canCarryToken("t", "dev", "other"), "tokens are bound to their DeviceId")
        assertFalse(LegacyMediaServerMapper.canCarryToken("t", null, null), "no legacy device id: cannot be sure")
        assertFalse(LegacyMediaServerMapper.canCarryToken(null, "dev", null))
    }

    // --- end to end: seed what v1.13.5 wrote, run the importer, look at the new store ---

    private fun seededSource() = FakeSource(
        entries = mapOf(1 to blob(den, attic), 2 to blob(syncedNoAddress)),
        trust = pins,
        secrets = mapOf(
            "device-id" to "0123456789abcdef0123456789abcdef",
            "cred:jellyfin:m-den:u-viewer" to credential("token-den", "viewer"),
            "cred:emby:m-attic:u-kid" to credential("token-attic"),
        ),
    )

    @Test
    fun `the released servers land in the new store with tokens and pins and per-profile ownership`() {
        val source = seededSource()
        val store = FakeStore()
        val result = importer(source, store).runOnce()

        assertTrue(result.completed)
        assertEquals(3, result.imported)
        assertEquals(1, result.needSignIn, "the server with no token on this device")
        val p1 = connections(store, 1)
        assertEquals(listOf("Den", "Attic"), p1.map { it.name })
        assertEquals(listOf("NAS"), connections(store, 2).map { it.name }, "profile ownership is kept")
        val den = p1.first { it.name == "Den" }
        assertEquals("token-den", store.values["credential.${den.credentialRef}"], "the token is local and carried")
        val attic = p1.first { it.name == "Attic" }
        assertEquals("token-attic", store.values["credential.${attic.credentialRef}"])
        val nas = connections(store, 2).single()
        assertNull(store.values["credential.${nas.credentialRef}"], "no token: lands as Sign in again, entry kept")
        assertEquals("0123456789abcdef0123456789abcdef", store.values["device.id"], "the id the tokens were issued for")
        assertEquals(setOf("192.168.1.20:8920"), LegacyMediaServerFormat.parsePins(store.values["trust.pins"]).keys)
        val sync = json.decodeFromString(ServerSyncState.serializer(), store.values["profile.1.sync"]!!)
        assertTrue(sync.pendingPush, "the normal media_servers sync pushes the imported servers (without tokens)")
        assertTrue(source.migrated)
        assertFalse(source.erased, "legacy values are left in place")
    }

    @Test
    fun `running again changes nothing`() {
        val source = seededSource()
        val store = FakeStore()
        importer(source, store).runOnce()
        val snapshot = store.values.toMap()
        val again = importer(source, store).runOnce()
        assertTrue(again.completed)
        assertEquals(0, again.imported)
        assertEquals(snapshot, store.values.toMap())
    }

    @Test
    fun `even without the marker a second run duplicates nothing`() {
        val source = seededSource()
        val store = FakeStore()
        importer(source, store).runOnce()
        source.migrated = false
        val result = importer(source, store).runOnce()
        assertEquals(0, result.imported)
        assertEquals(3, result.skipped)
        assertEquals(2, connections(store, 1).size)
    }

    @Test
    fun `servers the new store already has are neither duplicated nor overwritten`() {
        val store = FakeStore()
        val existing = ServerConnection(
            id = "keep", providerId = "jellyfin", name = "Den (new)", address = "https://new.example.com", remoteServerId = "m-den",
            remoteUserId = "u-viewer", userName = "viewer", credentialRef = "kk", enabled = true,
        )
        store.values["profile.1.connections"] = json.encodeToString(ListSerializer(ServerConnection.serializer()), listOf(existing))
        importer(seededSource(), store).runOnce()
        val p1 = connections(store, 1)
        assertEquals(listOf("Den (new)", "Attic"), p1.map { it.name })
        assertEquals("https://new.example.com", p1.first().address)
    }

    @Test
    fun `a token whose device identity does not match is not carried but the server is kept`() {
        val store = FakeStore(mutableMapOf("device.id" to "a-different-device-id-already-in-use"))
        val result = importer(seededSource(), store).runOnce()
        assertEquals(3, result.needSignIn)
        assertEquals(3, result.imported)
        assertTrue(store.values.keys.none { it.startsWith("credential.") })
        val den = connections(store, 1).first { it.name == "Den" }
        assertEquals("viewer", den.userName, "address and user stay prefilled")
        assertEquals("https://jf.example.com/jellyfin", den.address)
        assertEquals("a-different-device-id-already-in-use", store.values["device.id"], "an existing identity is never replaced")
    }

    @Test
    fun `unreadable bytes in the new store are never overwritten`() {
        val store = FakeStore(mutableMapOf("profile.1.connections" to "{corrupt"))
        val source = seededSource()
        val result = importer(source, store).runOnce()
        assertFalse(result.completed)
        assertEquals("{corrupt", store.values["profile.1.connections"])
        assertFalse(source.migrated, "retried next launch")
        assertEquals(listOf("NAS"), connections(store, 2).map { it.name }, "the other profile still imported")
    }

    @Test
    fun `an unreadable legacy entry keeps the marker unset`() {
        val source = FakeSource(entries = mapOf(1 to """{"version":1,"entries":[$den,{"key":"broken"}]}"""))
        val result = importer(source, FakeStore()).runOnce()
        assertEquals(1, result.imported)
        assertFalse(result.completed)
        assertFalse(source.migrated)
    }

    @Test
    fun `nothing to import completes without writing`() {
        val store = FakeStore()
        val source = FakeSource()
        val result = importer(source, store).runOnce()
        assertTrue(result.completed)
        assertTrue(store.values.isEmpty())
        assertTrue(source.migrated)
    }

    @Test
    fun `a pin the new store already has wins over the legacy one`() {
        val store = FakeStore(mutableMapOf("trust.pins" to """{"192.168.1.20:8920":"NEWPIN"}"""))
        importer(seededSource(), store).runOnce()
        assertNotNull(store.values["trust.pins"])
        assertTrue(store.values["trust.pins"]!!.contains("NEWPIN"))
        assertFalse(store.values["trust.pins"]!!.contains("AB12CD34"))
    }
}
