package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/*
 * One-time import of the saved Jellyfin / Emby servers the RELEASED fork stack (features/mediaserver, shipped in v1.13.0 - v1.13.5
 * on Mobile and Apple TV) kept, into upstream's features/servers store. Self-contained: it reads the legacy storage by its keys and
 * JSON shape only, so the deleted fork code is not needed. Released format (identical in every release that had it):
 *
 *  entries   Android SharedPreferences "nuvio_mediaserver" key "entries_<profileId>"; iOS/tvOS NSUserDefaults "mediaserver_entries_<profileId>"
 *            = {"version":1,"entries":[{key,type,machineId,userId,name,address?,syncAddress,enabled,userName?,homeRows[],homeLibraries{}}]}
 *  pins      "trust" / "mediaserver_trust" = {"host:port":"SHA256 HEX UPPERCASE"}  (same shape and fingerprint as upstream's pin store)
 *  tokens    secure store item "cred:<type>:<machineId>:<userId>" = {"accessToken":"...","userName":"..."?}
 *            (Android Keystore AES-GCM in prefs "nuvio_mediaserver_tokens" item.<key>; iOS/tvOS Keychain service
 *            "com.nuvio.media.mediaserver" account "item.<key>")
 *  device    secure store item "device-id": the MediaBrowser DeviceId those tokens were issued for
 */

/** Read-only view of the legacy storage (one implementation per platform) plus the migration marker, which lives with it. */
internal interface LegacyMediaServerSource {
    /** False until the platform storage can be read (Android hands it a Context a moment after start): the import waits instead of concluding "nothing to import". */
    fun isAvailable(): Boolean
    fun entryProfileIds(): List<Int>
    fun entriesJson(profileId: Int): String?
    fun trustJson(): String?

    /** The decrypted secure-store item [key] (`cred:...`, `device-id`), or null. Never removes anything. */
    fun secret(key: String): String?
    fun isMigrated(): Boolean
    fun markMigrated()

    /** Account wipe: forget every legacy value (and the marker's reason to exist). */
    fun eraseAll()
}

internal interface ServerKeyValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal expect object PlatformLegacyMediaServerSource : LegacyMediaServerSource

internal object PlatformServerKeyValueStore : ServerKeyValueStore {
    override fun read(key: String): String? = ServerStorage.read(key)
    override fun write(key: String, value: String?) = ServerStorage.write(key, value)
}

internal data class LegacyServerEntry(
    val key: String,
    /** "jellyfin" or "emby" (the legacy wire value, the same ids upstream's providers use). */
    val type: String,
    val machineId: String,
    val userId: String,
    val name: String,
    val address: String?,
    val enabled: Boolean,
    val userName: String?,
    val homeRows: Set<String>,
    val homeLibraries: Map<String, String>,
) {
    /** `{type}:{machineId}:{userId}` - what the secure store's credential key embeds. */
    val serverKey: String get() = "$type:$machineId:$userId"
}

/** Pure parsing of the legacy storage values. Tolerant: an unreadable entry is counted and skipped, never fatal. */
internal object LegacyMediaServerFormat {
    class Parsed(val entries: List<LegacyServerEntry>, val unreadable: Int)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseEntries(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(emptyList(), 0)
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return Parsed(emptyList(), 1)
        val array = root["entries"] as? JsonArray ?: return Parsed(emptyList(), 1)
        var unreadable = 0
        val entries = array.mapNotNull { element ->
            parseEntry(element).also { if (it == null) unreadable++ }
        }
        return Parsed(entries, unreadable)
    }

    private fun parseEntry(element: kotlinx.serialization.json.JsonElement): LegacyServerEntry? {
        val o = element as? JsonObject ?: return null
        fun str(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val type = str("type")?.takeIf { it == "jellyfin" || it == "emby" } ?: return null
        val machineId = str("machineId")?.takeIf { it.isNotBlank() } ?: return null
        val userId = str("userId")?.takeIf { it.isNotBlank() } ?: return null
        return LegacyServerEntry(
            key = str("key") ?: "$type|$machineId|$userId",
            type = type,
            machineId = machineId,
            userId = userId,
            name = str("name")?.takeIf { it.isNotBlank() } ?: machineId,
            address = str("address")?.takeIf { it.isNotBlank() },
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            userName = str("userName")?.takeIf { it.isNotBlank() },
            homeRows = (o["homeRows"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
            homeLibraries = (o["homeLibraries"] as? JsonObject)
                ?.mapNotNull { (id, name) -> (name as? JsonPrimitive)?.contentOrNull?.let { id to it } }
                ?.toMap().orEmpty(),
        )
    }

    /** The access token of a stored credential (`{"accessToken":"..."}`), or null when absent / blank / unreadable. */
    fun parseToken(raw: String?): String? =
        raw?.let { runCatching { json.parseToJsonElement(it).jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun parsePins(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() }.orEmpty()
            .filterValues { it.isNotBlank() }
}

/** Pure mapping of a legacy record to upstream's connection record. */
internal object LegacyMediaServerMapper {
    private val watchRows = setOf("continue_watching", "next_up")

    /**
     * [credentialRef] and [id] are minted by the caller. The address may be unknown (a server that arrived from another device with
     * "send address" off): the entry is still kept, with an empty address, and lands as "Sign in again" for the user to complete.
     */
    fun connectionFor(entry: LegacyServerEntry, id: String, credentialRef: String): ServerConnection = ServerConnection(
        id = id,
        providerId = entry.type,
        name = entry.name,
        address = entry.address.orEmpty(),
        remoteServerId = entry.machineId,
        remoteUserId = entry.userId,
        userName = entry.userName.orEmpty(),
        credentialRef = credentialRef,
        // (Desktop has no MIXED kind: MOVIE is a placeholder, the first refresh replaces every library with the server's real kinds.)
        // The fork kept no library selection, only the libraries the user had put on Home as rows of their own. They stay selected;
        // the first refresh lists the server's real libraries (new ones selected by default, upstream's rule).
        libraries = entry.homeLibraries.map { (libraryId, name) -> ServerLibrary(id = libraryId, name = name, kind = ServerMediaKind.MOVIE, selected = true) },
        enabled = entry.enabled,
        useCatalogMetadata = false,
        importWatchState = entry.homeRows.any { it in watchRows },
    )

    /**
     * A token is only carried when this device's server identity matches the one it was issued for (Jellyfin and Emby bind a token
     * to its DeviceId): no id stored yet, or the very same one. Otherwise the server lands as "Sign in again".
     */
    fun canCarryToken(token: String?, legacyDeviceId: String?, storedDeviceId: String?): Boolean =
        token != null && legacyDeviceId != null && (storedDeviceId == null || storedDeviceId == legacyDeviceId)

    fun identity(providerId: String, remoteServerId: String, remoteUserId: String) = "$providerId|$remoteServerId|$remoteUserId"
}

internal class LegacyMediaServerImport(
    private val source: LegacyMediaServerSource,
    private val store: ServerKeyValueStore,
    private val newId: (String) -> String = { prefix -> prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) } },
) {
    private val log = Logger.withTag("LegacyMediaServerImport")
    private val json = Json { ignoreUnknownKeys = true }

    class Result(val imported: Int, val skipped: Int, val needSignIn: Int, val completed: Boolean)

    /**
     * Imports once; safe to call on every launch. Non-destructive: legacy values are only read, and the marker is set only after every
     * profile was written AND read back. A failure leaves the marker unset (the next launch retries; de-duplication keeps it idempotent).
     */
    fun runOnce(): Result {
        if (source.isMigrated()) return Result(0, 0, 0, completed = true)
        var imported = 0
        var skipped = 0
        var needSignIn = 0
        var allOk = true
        val legacyDeviceId = runCatching { source.secret(DEVICE_ID) }.getOrNull()?.takeIf { it.isNotBlank() }
        var storedDeviceId = runCatching { store.read(DEVICE_KEY) }.getOrNull()?.takeIf { it.isNotBlank() }
        var carriedAny = false

        for (profileId in source.entryProfileIds()) {
            try {
                val parsed = LegacyMediaServerFormat.parseEntries(source.entriesJson(profileId))
                if (parsed.unreadable > 0) allOk = false // keep the marker unset: never drop what could not be read
                val existing = readConnections(profileId)
                if (existing == null) {
                    allOk = false // the new store holds bytes this build cannot read: never overwrite them with a shorter list
                    continue
                }
                val known = existing.map { LegacyMediaServerMapper.identity(it.providerId, it.remoteServerId, it.remoteUserId) }.toMutableSet()
                val added = mutableListOf<ServerConnection>()
                val credentials = mutableListOf<Pair<String, String>>()
                for (entry in parsed.entries) {
                    if (!known.add(LegacyMediaServerMapper.identity(entry.type, entry.machineId, entry.userId))) {
                        skipped++
                        continue
                    }
                    val token = LegacyMediaServerFormat.parseToken(runCatching { source.secret("cred:${entry.serverKey}") }.getOrNull())
                    val carry = LegacyMediaServerMapper.canCarryToken(token, legacyDeviceId, storedDeviceId)
                    val connection = LegacyMediaServerMapper.connectionFor(entry, id = newId("c"), credentialRef = newId("k"))
                    added += connection
                    if (carry) {
                        credentials += connection.credentialRef to token!!
                        carriedAny = true
                        storedDeviceId = storedDeviceId ?: legacyDeviceId
                    } else {
                        needSignIn++
                    }
                }
                if (added.isEmpty()) continue
                credentials.forEach { (ref, token) -> store.write("credential.$ref", token) }
                if (carriedAny && legacyDeviceId != null && store.read(DEVICE_KEY).isNullOrBlank()) store.write(DEVICE_KEY, legacyDeviceId)
                val all = existing + added
                store.write(connectionsKey(profileId), json.encodeToString(ListSerializer(ServerConnection.serializer()), all))
                // Verified: the new store reads back everything before this profile counts as imported.
                if (readConnections(profileId)?.size != all.size || credentials.any { (ref, token) -> store.read("credential.$ref") != token }) {
                    allOk = false
                    continue
                }
                markPushPending(profileId)
                imported += added.size
            } catch (error: Exception) {
                log.w(error) { "importing the legacy media servers of profile $profileId failed" }
                allOk = false
            }
        }
        if (!importPins()) allOk = false
        if (allOk) source.markMigrated()
        return Result(imported, skipped, needSignIn, completed = allOk)
    }

    private fun importPins(): Boolean = try {
        val legacy = LegacyMediaServerFormat.parsePins(source.trustJson())
        if (legacy.isNotEmpty()) {
            val serializer = MapSerializer(String.serializer(), String.serializer())
            val current = store.read(PINS_KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            // A pin the new store already has wins; pins are device-local and never synced.
            val merged = legacy.mapKeys { it.key.lowercase() } + current
            if (merged != current) store.write(PINS_KEY, json.encodeToString(serializer, merged))
            store.read(PINS_KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty().keys.containsAll(merged.keys)
        } else {
            true
        }
    } catch (error: Exception) {
        log.w(error) { "importing the legacy certificate pins failed" }
        false
    }

    /** The imported servers push through the normal media_servers sync (token blanked there); the engine only needs to know there is something to send. */
    private fun markPushPending(profileId: Int) {
        val current = store.read(syncKey(profileId))?.let { runCatching { json.decodeFromString(ServerSyncState.serializer(), it) }.getOrNull() } ?: ServerSyncState()
        store.write(syncKey(profileId), json.encodeToString(ServerSyncState.serializer(), current.copy(pendingPush = true)))
    }

    /** The connections already in the new store; empty when none, null when stored bytes cannot be read. */
    private fun readConnections(profileId: Int): List<ServerConnection>? {
        val raw = store.read(connectionsKey(profileId)) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ServerConnection.serializer()), raw) }.getOrNull()
    }

    private fun connectionsKey(profileId: Int) = "profile.$profileId.connections"
    private fun syncKey(profileId: Int) = "profile.$profileId.sync"

    private companion object {
        const val DEVICE_ID = "device-id"
        const val DEVICE_KEY = "device.id"
        const val PINS_KEY = "trust.pins"
    }
}
