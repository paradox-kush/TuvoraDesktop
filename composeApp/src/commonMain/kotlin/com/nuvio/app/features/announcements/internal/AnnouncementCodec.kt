package com.nuvio.app.features.announcements.internal

import com.nuvio.app.features.announcements.api.Announcement
import com.nuvio.app.features.announcements.api.AnnouncementKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One row of `get_app_announcements` — also the on-device cache format. */
@Serializable
internal data class AnnouncementRow(
    val id: String,
    val title: String = "",
    val body: String = "",
    @SerialName("cta_label") val ctaLabel: String? = null,
    @SerialName("cta_url") val ctaUrl: String? = null,
    val kind: String? = null,
    @SerialName("starts_at") val startsAt: String = "",
)

internal fun AnnouncementRow.toAnnouncement(): Announcement {
    val cta = AnnouncementPolicy.cta(ctaLabel, ctaUrl)
    return Announcement(
        id = id,
        title = title.trim(),
        body = body.trim(),
        ctaLabel = cta?.first,
        ctaUrl = cta?.second,
        kind = AnnouncementKind.fromWire(kind),
        startsAt = startsAt,
    )
}

internal fun Announcement.toRow(): AnnouncementRow = AnnouncementRow(
    id = id,
    title = title,
    body = body,
    ctaLabel = ctaLabel,
    ctaUrl = ctaUrl,
    kind = kind.wire,
    startsAt = startsAt,
)

/** JSON in and out of the cache. Every decode is total: bad input yields empty, never a throw. */
internal object AnnouncementCodec {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val rows = ListSerializer(AnnouncementRow.serializer())
    private val ids = ListSerializer(String.serializer())

    fun decodeWire(text: String?): List<Announcement> =
        text?.let { runCatching { json.decodeFromString(rows, it) }.getOrNull() }
            ?.map { it.toAnnouncement() }
            .orEmpty()

    /** Network path: a malformed response THROWS so the caller keeps its cache instead of
     *  overwriting it with an empty list. */
    fun decodeWireOrThrow(text: String): List<Announcement> =
        json.decodeFromString(rows, text).map { it.toAnnouncement() }

    fun encode(items: List<Announcement>): String = json.encodeToString(rows, items.map { it.toRow() })

    fun decodeIds(text: String?): Set<String> =
        text?.let { runCatching { json.decodeFromString(ids, it) }.getOrNull() }?.toSet().orEmpty()

    fun encodeIds(values: Set<String>): String = json.encodeToString(ids, values.toList())
}
