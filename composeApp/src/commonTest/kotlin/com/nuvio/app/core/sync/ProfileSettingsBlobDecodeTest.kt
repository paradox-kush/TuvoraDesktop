package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// B03-D3: the website wrote notifications_settings.episode_release_alerts_enabled as a {type,value}
// envelope while this blob models it as a flat Boolean. The whole decode threw, so no settings
// change from the web or any other phone applied on that profile until a phone overwrote the blob.
// Rows already written that way live in prod, so the decode must accept both shapes.
class ProfileSettingsBlobDecodeTest {
    private fun blob(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `flat boolean notifications flag decodes`() {
        val decoded = decodeMobileProfileSettingsBlob(
            blob("""{"version":3,"features":{"notifications_settings":{"episode_release_alerts_enabled":true}}}"""),
        )
        assertEquals(true, decoded.features.notificationsSettings.episodeReleaseAlertsEnabled)
    }

    @Test
    fun `website envelope notifications flag decodes to its value`() {
        val decoded = decodeMobileProfileSettingsBlob(
            blob(
                """{"version":3,"features":{
                  "theme_settings":{"amoled_enabled":{"type":"boolean","value":true}},
                  "notifications_settings":{"episode_release_alerts_enabled":{"type":"boolean","value":true}}
                }}""",
            ),
        )
        assertEquals(true, decoded.features.notificationsSettings.episodeReleaseAlertsEnabled)
        // the rest of the blob still applies instead of the whole pull being dropped
        assertEquals(
            blob("""{"amoled_enabled":{"type":"boolean","value":true}}"""),
            decoded.features.themeSettings,
        )
    }

    @Test
    fun `website envelope with false decodes to false`() {
        val decoded = decodeMobileProfileSettingsBlob(
            blob("""{"features":{"notifications_settings":{"episode_release_alerts_enabled":{"type":"boolean","value":false}}}}"""),
        )
        assertEquals(false, decoded.features.notificationsSettings.episodeReleaseAlertsEnabled)
    }

    @Test
    fun `missing notifications flag keeps the default`() {
        val decoded = decodeMobileProfileSettingsBlob(blob("""{"version":3,"features":{}}"""))
        assertEquals(false, decoded.features.notificationsSettings.episodeReleaseAlertsEnabled)
    }
}
