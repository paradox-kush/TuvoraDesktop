package com.nuvio.app.features.profiles

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val MAX_PROFILES = 6

/**
 * The account's anchor profile. It cannot be deleted, and other profiles inherit its addons and
 * plugins via [NuvioProfile.usesPrimaryAddons] / [NuvioProfile.usesPrimaryPlugins]. "Primary" is
 * this index, not a flag — which is why promoting a profile means swapping indexes.
 */
const val PRIMARY_PROFILE_INDEX = 1

@Serializable
data class NuvioProfile(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("profile_index") val profileIndex: Int = 1,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    @SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @SerialName("pin_locked_until") val pinLockedUntil: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ProfilePushPayload(
    @SerialName("profile_index") val profileIndex: Int,
    val name: String,
    @SerialName("avatar_color_hex") val avatarColorHex: String,
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class PinVerifyResult(
    val unlocked: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0,
    val message: String? = null,
    /**
     * The server refused because the profile already has a PIN and the current one was not supplied.
     *
     * Client-side only — the RPC signals this by raising, never in the result body — so it is
     * [Transient] and never crosses the wire. It exists because the local `pinEnabled` flag can be
     * stale (it comes from `sync_pull_profile_locks`, which fails whenever the session has lapsed),
     * and a stale `false` sends the user down the "set a new PIN" path that the server then rejects
     * with no way forward.
     */
    @Transient val currentPinRequired: Boolean = false,
)

data class ProfileState(
    val profiles: List<NuvioProfile> = emptyList(),
    val activeProfile: NuvioProfile? = null,
    val isLoaded: Boolean = false,
    val hasEverSelectedProfile: Boolean = false,
    val rememberLastProfileEnabled: Boolean = false,
)

@Serializable
data class AvatarCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("storage_path") val storagePath: String = "",
    val category: String = "character",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("bg_color") val bgColor: String? = null,
)

fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> Color(0xFF1E88E5)
        }
    }.getOrDefault(Color(0xFF1E88E5))
}

val PROFILE_COLORS = listOf(
    "#1E88E5", "#E53935", "#43A047", "#FB8C00",
    "#8E24AA", "#00ACC1", "#F4511E", "#3949AB",
    "#C0CA33", "#D81B60", "#00897B", "#5E35B1",
    "#7CB342", "#039BE5", "#FFB300", "#6D4C41",
)

// Resolve against the SELECTED backend, not SupabaseConfig.URL. Hardcoding the hosted URL happened
// to be right only while the hosted backend was active - after a switch to 'nuvio' the avatars were
// still fetched from the hosted project. SyncBackendConfig already derives the correct base per
// backend, so ask it.
fun avatarStorageUrl(storagePath: String): String =
    com.nuvio.app.core.network.SupabaseProvider.selectedBackend.avatarStorageUrl(storagePath)

fun normalizedAvatarUrl(url: String?): String? =
    url?.trim()?.takeIf { it.isValidAvatarUrl() }

fun String.isValidAvatarUrl(): Boolean {
    val value = trim()
    return value.length <= 2048 &&
        !value.any { it.isWhitespace() } &&
        (value.startsWith("https://") || value.startsWith("http://"))
}

fun profileAvatarImageUrl(profile: NuvioProfile, avatar: AvatarCatalogItem?): String? =
    normalizedAvatarUrl(profile.avatarUrl)
        ?: avatar
            ?.storagePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::avatarStorageUrl)
