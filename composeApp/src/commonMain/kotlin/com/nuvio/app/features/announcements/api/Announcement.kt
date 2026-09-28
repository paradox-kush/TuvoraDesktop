package com.nuvio.app.features.announcements.api

/**
 * One server-authored announcement (Supabase RPC `get_app_announcements`). Neutral value type:
 * [ctaLabel]/[ctaUrl] are already sanitised — both are non-null only when the label is present
 * and the url is a safe https link, so a consumer can render the button without re-checking.
 */
data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val ctaLabel: String?,
    val ctaUrl: String?,
    val kind: AnnouncementKind,
    val startsAt: String,
)

enum class AnnouncementKind(val wire: String) {
    Info("info"),
    Update("update"),
    Policy("policy"),
    ;

    companion object {
        /** Unknown kinds (a newer backend) degrade to [Info] rather than dropping the row. */
        fun fromWire(value: String?): AnnouncementKind =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: Info
    }
}
