package com.nuvio.app.features.announcements.internal

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.syncDevicePlatform
import com.nuvio.app.features.announcements.api.Announcement
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `get_app_announcements` — works with the anon key or a signed-in session; server-ordered, max 3. */
internal suspend fun fetchAnnouncementsFromSupabase(): List<Announcement> {
    val result = SupabaseProvider.client.postgrest.rpc(
        "get_app_announcements",
        // "desktop" on the desktop JVM target (the Android/iOS targets of this fork say "mobile").
        buildJsonObject { put("p_platform", syncDevicePlatform()) },
    )
    return AnnouncementCodec.decodeWireOrThrow(result.data)
}
