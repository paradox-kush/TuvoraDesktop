package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/**
 * What the server knows about a playlist a provider installed (one row of `get_managed_playlists`).
 * The pulled playlist rows themselves carry no managed flag, so this map is the only source of "this
 * playlist is managed" — see [ManagedInfoRepository].
 */
@Serializable
data class ManagedInfo(
    val playlistKey: String,
    val providerName: String,
    val serviceName: String? = null,
    val support: ProviderSupport = ProviderSupport.NONE,
    /** When the provider last changed this service (ISO-8601). Absent from older servers: omit "updated <date>". */
    val serviceUpdatedAt: String? = null,
)

/** The things about a playlist a person can change, for [ManagedPlaylistPolicy.editableFields]. */
enum class PlaylistField {
    NAME,
    /** Server address, username, password, MAC, portal — what the provider installed. */
    SERVER_LOGIN,
    BACKUP_SERVERS,
    EPG_URL,
    USER_AGENT,
    DNS_PROVIDER,
    AUTO_REFRESH,
    ENABLED,
    CONTENT_TYPES,
    CATEGORIES,
}

/**
 * Which playlists are managed and what that changes. A managed playlist is the provider's: they keep its
 * server, login, backups, guide address and user agent up to date, and the customer can rename it,
 * switch it on or off, choose its categories — or Detach it and take it over.
 */
object ManagedPlaylistPolicy {
    /** A playlist is managed iff its key is in the map [ManagedInfoRepository] holds for the profile. */
    fun isManaged(playlistKey: String, map: Map<String, ManagedInfo>): Boolean = playlistKey in map

    fun ownerLabel(info: ManagedInfo): String = "Managed by ${info.providerName}"

    /** Every field of an unmanaged playlist; for a managed one only what the provider does not own. */
    fun editableFields(managed: Boolean): Set<PlaylistField> =
        if (!managed) PlaylistField.entries.toSet()
        else setOf(
            PlaylistField.NAME, PlaylistField.ENABLED, PlaylistField.CONTENT_TYPES,
            PlaylistField.CATEGORIES, PlaylistField.DNS_PROVIDER, PlaylistField.AUTO_REFRESH,
        )

    /** The Edit Playlist screen shows the name only for a managed playlist (the rest is locked, not hidden). */
    fun editScreenFields(managed: Boolean): Set<PlaylistField> =
        if (managed) setOf(PlaylistField.NAME) else PlaylistField.entries.toSet()

    /** Server / login fields are never offered for a managed playlist; a locked note stands in for them. */
    fun showEditServerLogin(managed: Boolean): Boolean = !managed

    /** Detach exists only for a managed playlist. */
    fun showDetach(managed: Boolean): Boolean = managed

    /** Source-type chips are not selectable in edit mode, for any playlist: the type is decided at add time. */
    fun sourceTypeSelectable(editing: Boolean): Boolean = !editing
}
