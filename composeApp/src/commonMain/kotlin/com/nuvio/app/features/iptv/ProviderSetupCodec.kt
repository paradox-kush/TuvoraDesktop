package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Provider-controlled text, made safe to show: control, format and bidirectional-override characters are
 * removed (an unterminated RLO would reorder the text that follows it in the same dialog, and a zero-width
 * name is no name), and the result is trimmed. Older server versions do not strip these (security L1).
 */
internal object ProviderText {
    private val unsafe = setOf(
        CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR,
    )

    fun clean(value: String?, max: Int = 80): String =
        value.orEmpty().filter { it.category !in unsafe }.trim().take(max).trim()
}

/** Lenient wire decoding of `get_managed_playlists` / the redeem and preview bodies (a bad row is skipped, never fatal). */
internal object ProviderSetupCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** At most this many playlists / add-ons are read from a preview: more is not a plausible setup. */
    const val MAX_PREVIEW_ROWS = 20

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
                providerName = ProviderText.clean(o["provider_name"].str()).ifEmpty { "your provider" },
                serviceName = ProviderText.clean(o["service_name"].str()).takeIf { it.isNotEmpty() },
                support = support(o["support"]),
                serviceUpdatedAt = o["service_updated_at"].str()?.takeIf { it.isNotBlank() },
            )
        }

    /** The preview route's `{ "preview": {...} }` body, or null when it is not a usable preview. */
    fun preview(body: String): SetupPreview? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val p = root["preview"] as? JsonObject ?: return null
        val provider = ProviderText.clean(p["provider_name"].str()).takeIf { it.isNotEmpty() } ?: return null
        val playlists = (p["playlists"] as? JsonArray).orEmpty().take(MAX_PREVIEW_ROWS).map { el ->
            val o = el as? JsonObject
            SetupPreviewPlaylist(
                name = ProviderText.clean(o?.get("name").str()).ifEmpty { "Playlist" },
                sourceType = ProviderText.clean(o?.get("source_type").str(), max = 20).ifEmpty { SOURCE_TYPE_XTREAM },
            )
        }
        // Add-ons are shown by NAME only; anything URL-shaped is dropped (an add-on URL can embed a key).
        val addons = (p["addons"] as? JsonArray).orEmpty()
            .mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { ProviderText.clean(it) } }
            .filter { it.isNotEmpty() && !it.contains("://") }
            .take(MAX_PREVIEW_ROWS)
        return SetupPreview(
            providerName = provider,
            support = support(p["support"]),
            packageName = ProviderText.clean(p["package_name"].str()),
            playlists = playlists,
            addons = addons,
            status = p["status"].str(),
            expiresAt = p["expires_at"].str(),
        )
    }
}
