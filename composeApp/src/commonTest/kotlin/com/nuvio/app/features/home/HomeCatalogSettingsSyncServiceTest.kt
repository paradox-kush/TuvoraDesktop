package com.nuvio.app.features.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun `shared settings merge preserves unknown remote fields`() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("show_catalog_type", false)
        }
        val local = buildJsonObject {
            put("show_catalog_type", true)
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.content.toBoolean())
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }

    // The sync blob is opaque jsonb: adding a key needs no backend migration. This proves the new
    // show_live_on_home key serializes and round-trips, and that a legacy blob lacking it defaults to
    // true (current behaviour) via kotlinx ignoreUnknownKeys/default handling.
    @Test
    fun `show live on home round-trips through the opaque sync blob`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val encoded = json.encodeToString(
            SyncHomeCatalogPayload.serializer(),
            SyncHomeCatalogPayload(showLiveOnHome = false),
        )
        assertEquals(true, encoded.contains("\"show_live_on_home\":false"), "field must serialize under its snake_case key")

        val decoded = json.decodeFromString(SyncHomeCatalogPayload.serializer(), encoded)
        assertEquals(false, decoded.showLiveOnHome, "value must survive a serialize/deserialize round-trip")

        val legacyBlob = json.decodeFromString(
            SyncHomeCatalogPayload.serializer(),
            "{\"show_catalog_type\":true}",
        )
        assertEquals(true, legacyBlob.showLiveOnHome, "a legacy blob without the key defaults to true")
    }
}

class HomeSectionsSyncTest {
    private val sections = HomeSectionsConfig(
        customized = true,
        sections = listOf(
            HomeSectionEntry("continue_watching", enabled = true),
            HomeSectionEntry("hero", enabled = false),
            HomeSectionEntry("from_the_future", enabled = true),
            HomeSectionEntry("catalogs", enabled = true),
        ),
    )

    private fun encode(payload: SyncHomeCatalogPayload): JsonObject =
        homeCatalogSyncJson.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject

    @Test
    fun `older payload without home_sections decodes with no layout`() {
        val older = buildJsonObject {
            put("show_catalog_type", true)
            put("show_live_on_home", false)
        }

        val decoded = decodeSharedHomeCatalogPayload(older)

        assertNull(decoded.homeSections, "absent key means no layout, so the device default applies")
        assertEquals(false, decoded.showLiveOnHome, "the rest of the payload still decodes")
    }

    @Test
    fun `home_sections round-trips with the shared key and field names`() {
        val encoded = encode(SyncHomeCatalogPayload(homeSections = sections))

        val raw = encoded.getValue("home_sections").jsonObject
        assertEquals(true, raw.getValue("customized").jsonPrimitive.boolean, "customized flag")
        val first = raw.getValue("sections").jsonArray.first().jsonObject
        assertEquals("continue_watching", first.getValue("id").jsonPrimitive.content, "entry id key")
        assertEquals(true, first.getValue("enabled").jsonPrimitive.boolean, "entry enabled key")
        assertEquals(sections, decodeSharedHomeCatalogPayload(encoded).homeSections, "round trip keeps unknown ids and order")
    }

    @Test
    fun `a device that never set a layout does not send home_sections`() {
        val encoded = encode(SyncHomeCatalogPayload(homeSections = null))

        assertEquals(false, encoded.containsKey("home_sections"), "null must be omitted, not written as JSON null")
    }

    @Test
    fun `merge on push keeps another device's home_sections`() {
        val remote = buildJsonObject {
            put("home_sections", homeCatalogSyncJson.encodeToJsonElement(HomeSectionsConfig.serializer(), sections))
            put("show_catalog_type", false)
        }
        val local = encode(SyncHomeCatalogPayload(showCatalogType = true, homeSections = null))

        val merged = mergeHomeCatalogSettingsJson(remoteJson = remote, localJson = local)

        assertEquals(sections, decodeSharedHomeCatalogPayload(merged).homeSections, "remote layout survives")
        assertEquals(true, merged.getValue("show_catalog_type").jsonPrimitive.boolean, "local top-level key still wins")
    }

    @Test
    fun `merge on push lets a Reset to Default overwrite a customised remote layout`() {
        val remote = buildJsonObject {
            put("home_sections", homeCatalogSyncJson.encodeToJsonElement(HomeSectionsConfig.serializer(), sections))
        }
        val local = encode(SyncHomeCatalogPayload(homeSections = HomeSectionOrderPolicy.reset()))

        val merged = decodeSharedHomeCatalogPayload(mergeHomeCatalogSettingsJson(remote, local))

        assertEquals(false, merged.homeSections?.customized, "reset is written explicitly, so it replaces the remote value")
    }

    @Test
    fun `malformed home_sections is dropped and does not sink the payload`() {
        val broken = buildJsonObject {
            put("show_live_on_home", false)
            put("home_sections", "not an object")
        }

        val decoded = decodeSharedHomeCatalogPayload(broken)

        assertNull(decoded.homeSections, "unusable layout is ignored")
        assertEquals(false, decoded.showLiveOnHome, "other settings still apply")
    }
}
