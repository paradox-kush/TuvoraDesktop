package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F46 — Device ID 2 / Signature / STB model / HW version ride every place a Stalker playlist's
 * identity already rides: the sync row (both directions), the 3-way edit merge, the managed lock,
 * the "same connection" verify gate, and old persisted JSON.
 */
class StalkerIdentitySyncTest {

    private val stalker = XtreamAccount(
        id = "stalker|http://p:8080|00:1A:79:AA:BB:CC", name = "Portal", baseUrl = "http://p:8080",
        username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:AA:BB:CC",
    )
    private val full = stalker.copy(deviceId2 = "DEV2", signature = "SIG", stbModel = "MAG254", hwVersion = "2.6-IB-00")

    @Test
    fun `push carries the entered fields`() {
        val row = playlistPushPayload(listOf(full)).single().jsonObject
        assertEquals("DEV2", row["device_id2"]!!.jsonPrimitive.content)
        assertEquals("SIG", row["signature"]!!.jsonPrimitive.content)
        assertEquals("MAG254", row["stb_model"]!!.jsonPrimitive.content)
        assertEquals("2.6-IB-00", row["hw_version"]!!.jsonPrimitive.content)
    }

    /**
     * The server keeps a column whose key is ABSENT (so a pre-F46 build can never wipe it). A
     * cleared field therefore has to travel as an explicit null, or it could never be cleared.
     */
    @Test
    fun `push sends cleared fields as explicit nulls`() {
        val row = playlistPushPayload(listOf(stalker)).single().jsonObject
        for (key in listOf("device_id2", "signature", "stb_model", "hw_version")) {
            assertTrue(key in row, "$key must be present")
            assertEquals(JsonNull, row[key], "$key must be null")
        }
    }

    @Test
    fun `non stalker rows never carry the stalker identity keys`() {
        val xtream = XtreamAccount(id = "x", name = "x", baseUrl = "http://h", username = "u", password = "p")
        val row = playlistPushPayload(listOf(xtream)).single().jsonObject
        assertFalse("device_id2" in row)
        assertFalse("stb_model" in row)
    }

    @Test
    fun `pull maps the columns and blank reads as not entered`() {
        val acc = PlaylistRow(
            sourceType = "stalker", portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
            deviceId2 = "DEV2", signature = "SIG", stbModel = "MAG254", hwVersion = "2.6-IB-00",
        ).toAccount()!!
        assertEquals("DEV2", acc.deviceId2)
        assertEquals("SIG", acc.signature)
        assertEquals("MAG254", acc.stbModel)
        assertEquals("2.6-IB-00", acc.hwVersion)
        val blank = PlaylistRow(
            sourceType = "stalker", portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
            deviceId2 = " ", stbModel = "",
        ).toAccount()!!
        assertNull(blank.deviceId2)
        assertNull(blank.stbModel)
    }

    @Test
    fun `a pushed row pulls back to the same account`() {
        val json = Json { ignoreUnknownKeys = true }
        val wire = playlistPushPayload(listOf(full)).single()
        val pulled = json.decodeFromJsonElement(PlaylistRow.serializer(), wire).toAccount()!!
        assertEquals(playlistSyncKey(full), playlistSyncKey(pulled))
    }

    @Test
    fun `an identity edit is a connection change`() {
        assertTrue(full.sameConnectionAs(full))
        assertFalse(stalker.copy(deviceId2 = "DEV2").sameConnectionAs(stalker))
        assertFalse(stalker.copy(signature = "SIG").sameConnectionAs(stalker))
        assertFalse(stalker.copy(stbModel = "MAG254").sameConnectionAs(stalker))
        assertFalse(stalker.copy(hwVersion = "2.6-IB-00").sameConnectionAs(stalker))
    }

    @Test
    fun `a stale form keeps the identity another device set`() {
        val base = stalker
        val server = stalker.copy(stbModel = "MAG322", hwVersion = "HW-S")
        val edit = stalker.copy(name = "Renamed", deviceId2 = "DEV2")
        val merged = mergeEditOntoServer(server, base, edit)
        assertEquals("MAG322", merged.stbModel, "untouched field keeps the server value")
        assertEquals("HW-S", merged.hwVersion)
        assertEquals("DEV2", merged.deviceId2, "the field this edit changed wins")
        assertEquals("Renamed", merged.name)
    }

    @Test
    fun `a managed playlist keeps the installed identity`() {
        val edited = full.copy(deviceId2 = "X", signature = "Y", stbModel = "Z", hwVersion = "W", name = "n")
        val locked = ManagedEditPolicy.lockProviderFields(full, edited)
        assertEquals(full.copy(name = "n"), locked)
    }

    @Test
    fun `json persisted before F46 loads with the fields unset`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"id":"s","name":"n","baseUrl":"http://p","username":"","password":"",
            "sourceType":"stalker","macAddress":"00:1A:79:AA:BB:CC","deviceId":"DEV1"}"""
        val acc = json.decodeFromString(XtreamAccount.serializer(), old)
        assertEquals("DEV1", acc.deviceId)
        assertNull(acc.deviceId2)
        assertNull(acc.signature)
        assertNull(acc.stbModel)
        assertNull(acc.hwVersion)
    }

    @Test
    fun `the form builder trims and drops blanks`() {
        val acc = stalkerAccountFromForm(
            XtreamFormInput(
                serverUrl = "http://p:8080", username = "", password = "", name = null, epgUrl = null,
                dnsProvider = "system", autoRefreshHours = 24, sourceType = SOURCE_TYPE_STALKER,
                macAddress = "00:1A:79:AA:BB:CC", deviceId2 = " DEV2 ", signature = "  ", stbModel = "MAG254 ",
                hwVersion = "",
            )
        )!!
        assertEquals("DEV2", acc.deviceId2)
        assertNull(acc.signature)
        assertEquals("MAG254", acc.stbModel)
        assertNull(acc.hwVersion)
    }
}
