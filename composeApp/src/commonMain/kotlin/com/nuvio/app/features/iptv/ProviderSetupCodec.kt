package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lenient wire decoding of `get_managed_playlists` / the redeem and preview bodies (a bad row is skipped, never fatal). */
internal object ProviderSetupCodec {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun support(element: JsonElement?): ProviderSupport {
        val o = element as? JsonObject ?: return ProviderSupport.NONE
        return ProviderSupport(
            whatsapp = o["whatsapp"].str(), telegram = o["telegram"].str(),
            email = o["email"].str(), website = o["website"].str(),
        )
    }

    fun managedPlaylists(element: JsonElement?): List<ManagedInfo> =
        (element as? JsonArray).orEmpty().mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val key = o["playlist_key"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ManagedInfo(
                playlistKey = key,
                providerName = o["provider_name"].str()?.trim().orEmpty().ifEmpty { "your provider" },
                serviceName = o["service_name"].str()?.trim()?.takeIf { it.isNotEmpty() },
                support = support(o["support"]),
                serviceUpdatedAt = o["service_updated_at"].str()?.takeIf { it.isNotBlank() },
            )
        }

    /** The preview route's `{ "preview": {...} }` body, or null when it is not a usable preview. */
    fun preview(body: String): SetupPreview? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val p = root["preview"] as? JsonObject ?: return null
        val provider = p["provider_name"].str()?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: return null
        val playlists = (p["playlists"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject
            SetupPreviewPlaylist(
                name = o?.get("name").str()?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "Playlist",
                sourceType = o?.get("source_type").str()?.trim()?.take(20)?.takeIf { it.isNotEmpty() } ?: SOURCE_TYPE_XTREAM,
            )
        }
        // Add-ons are shown by NAME only; anything URL-shaped is dropped (an add-on URL can embed a key).
        val addons = (p["addons"] as? JsonArray).orEmpty()
            .mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.take(80) }
            .filter { it.isNotEmpty() && !it.contains("://") }
        return SetupPreview(
            providerName = provider,
            support = support(p["support"]),
            packageName = p["package_name"].str()?.trim()?.take(80).orEmpty(),
            playlists = playlists,
            addons = addons,
            status = p["status"].str(),
            expiresAt = p["expires_at"].str(),
        )
    }
}
