package com.nuvio.app.features.servers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class SyncedServer(
    val id: String,
    @SerialName("provider_id") val providerId: String,
    val name: String,
    val address: String,
    @SerialName("remote_server_id") val remoteServerId: String,
    @SerialName("remote_user_id") val remoteUserId: String,
    @SerialName("user_name") val userName: String,
    /** Always "" on the wire: access tokens are per-device and never sync (see [ServerTokenPolicy]). */
    val token: String = "",
    val libraries: List<SyncedLibrary> = emptyList(),
    val enabled: Boolean = true,
    @SerialName("use_catalog_metadata") val useCatalogMetadata: Boolean = false,
    @SerialName("import_watch_state") val importWatchState: Boolean? = null,
) {
    val key: String
        get() = serverKey(providerId, remoteServerId, remoteUserId)

    override fun toString(): String = "SyncedServer(id=$id, provider=$providerId)"
}

@Serializable
data class SyncedLibrary(
    val id: String,
    val name: String,
    val kind: String,
    val selected: Boolean,
)

@Serializable
internal data class ServerSyncState(
    val pendingPush: Boolean = false,
    val syncedKeys: List<String>? = null,
)

class ServerSyncSnapshot(
    val profileId: Int,
    val version: Long,
    val servers: List<SyncedServer>,
    val pendingPush: Boolean,
    val syncedKeys: Set<String>?,
)

private val syncJson = Json { encodeDefaults = true }

fun List<SyncedServer>.toSyncPayload(): JsonElement = syncJson.encodeToJsonElement(this)

fun serverKey(providerId: String, remoteServerId: String, remoteUserId: String): String =
    "$providerId|$remoteServerId|$remoteUserId"

internal fun mergeSyncedServers(
    local: List<SyncedServer>,
    remote: List<SyncedServer>,
    syncedKeys: Set<String>?,
): List<SyncedServer> {
    val localByKey = local.associateBy { it.key }
    val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
    val kept = remote.map { it.copy(token = "") }.map { server ->
        localByKey[server.key]?.let { local ->
            server.copy(id = local.id, token = "", importWatchState = server.importWatchState ?: local.importWatchState)
        } ?: server
    }
    val added = local.filter { it.key !in remoteKeys && (syncedKeys == null || it.key !in syncedKeys) }
    return kept + added
}

/** The wire form of a connection: everything except the access token, which stays on this device. */
internal fun ServerConnection.toSynced() = SyncedServer(
    id = id,
    providerId = providerId,
    name = name,
    address = address,
    remoteServerId = remoteServerId,
    remoteUserId = remoteUserId,
    userName = userName,
    token = "",
    libraries = libraries.map { SyncedLibrary(it.id, it.name, it.kind.contentType, it.selected) },
    enabled = enabled,
    useCatalogMetadata = useCatalogMetadata,
    importWatchState = importWatchState,
)

internal fun SyncedServer.toConnection(id: String, credentialRef: String) = ServerConnection(
    id = id,
    providerId = providerId,
    name = name,
    address = address,
    remoteServerId = remoteServerId,
    remoteUserId = remoteUserId,
    userName = userName,
    credentialRef = credentialRef,
    libraries = libraries.mapNotNull { library ->
        ServerMediaKind.entries.firstOrNull { it.contentType == library.kind }?.let { kind ->
            ServerLibrary(library.id, library.name, kind, library.selected)
        }
    },
    enabled = enabled,
    useCatalogMetadata = useCatalogMetadata,
    importWatchState = importWatchState ?: false,
)

/**
 * Media-server access tokens are device-local (user decision 2026-10-10): the pushed payload carries an empty token,
 * and nothing pulled from another device is ever written over, or into, the local credential store. A server that
 * arrives without a local token is signed out on this device and asks for a sign-in ([needsSignIn]).
 */
internal object ServerTokenPolicy {
    /** The token to hold after a pull: the local one, whatever the remote row carries. */
    fun afterPull(local: String?, @Suppress("UNUSED_PARAMETER") remote: String?): String? = local?.takeIf { it.isNotBlank() }

    fun needsSignIn(localToken: String?): Boolean = localToken.isNullOrBlank()
}
