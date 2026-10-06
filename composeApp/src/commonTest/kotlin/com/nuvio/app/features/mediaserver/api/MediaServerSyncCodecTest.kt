package com.nuvio.app.features.mediaserver.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared golden vectors for the media-server playlist key. IDENTICAL to nuvio-web
 * `src/lib/iptv/mediaServer.test.ts` and the SQL golden `supabase/tests/playlist_key_media_server_golden.sql`
 * (lane JW) - change all of them together.
 */
class MediaServerSyncCodecTest {
    private val m = "6f3c1a9e2b7d4c58a1e0f9d8c7b6a543"
    private val u = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

    private data class Vec(val type: String, val machineId: String, val userId: String, val expected: String?)

    private val vectors = listOf(
        Vec("jellyfin", m, u, "jellyfin|$m|$u"),
        Vec("emby", m, u, "emby|$m|$u"),
        Vec("jellyfin", "  $m  ", "\t$u\n", "jellyfin|$m|$u"),
        Vec("jellyfin", "ABCDEF0123456789ABCDEF0123456789", u, "jellyfin|ABCDEF0123456789ABCDEF0123456789|$u"),
        Vec("jellyfin", m, "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", "jellyfin|$m|0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"),
        Vec("jellyfin", "", u, null),
        Vec("jellyfin", m, "   ", null),
        // a typed address can never become the identity (the whole point of an explicit key)
        Vec("jellyfin", "http://192.168.1.5:8096", u, null),
        Vec("jellyfin", "jellyfin.example.com:8096", u, null),
        Vec("jellyfin", "a|b", u, null),
        Vec("jellyfin", m, "u|v", null),
        Vec("jellyfin", "has space", u, null),
        Vec("xtream", m, u, null),
        Vec("plex", m, u, null), // parked (design P2): no key until it is designed
        Vec("Jellyfin", m, u, null), // the type is the lowercase wire value
    )

    @Test
    fun everyGoldenVectorBuildsItsExpectedKey() {
        val failures = vectors.filter { MediaServerSyncCodec.playlistKey(it.type, it.machineId, it.userId) != it.expected }
            .map { "$it -> ${MediaServerSyncCodec.playlistKey(it.type, it.machineId, it.userId)}" }
        assertEquals(emptyList(), failures, "golden vectors")
        assertEquals(15, vectors.size, "all 15 shared vectors present")
    }

    @Test
    fun aKeyNeverLooksLikeAnXtreamKey() {
        val key = MediaServerSyncCodec.playlistKey(MediaServerType.JELLYFIN, m, u)!!
        assertTrue(key.startsWith("jellyfin|"))
        assertFalse(key.startsWith("http"))
        assertEquals(3, key.split('|').size)
    }

    private fun entry(syncAddress: Boolean = true, address: String? = "http://192.168.1.5:8096") = MediaServerEntry(
        key = "jellyfin|$m|$u", type = MediaServerType.JELLYFIN, machineId = m, userId = u,
        name = "Home server", address = address, syncAddress = syncAddress, enabled = true, userName = "kid",
        homeRows = setOf(MediaServerHomeRow.NEXT_UP),
    )

    @Test
    fun pushRowCarriesTheServerEntryAndNeverACredential() {
        val row = MediaServerSyncCodec.pushRow(entry(), sortOrder = 2)
        assertEquals("jellyfin|$m|$u", row["playlist_key"]!!.jsonPrimitive.content)
        assertEquals("jellyfin", row["source_type"]!!.jsonPrimitive.content)
        assertEquals("jellyfin://$m", row["url"]!!.jsonPrimitive.content)
        assertEquals(u, row["username"]!!.jsonPrimitive.content)
        assertEquals("Home server", row["name"]!!.jsonPrimitive.content)
        assertTrue(row["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(2, row["sort_order"]!!.jsonPrimitive.int)
        assertEquals("http://192.168.1.5:8096", row["base_url"]!!.jsonPrimitive.content)
        val forbidden = listOf("password", "stalker_password", "mac_address", "serial_number", "device_id", "device_id2", "signature", "token", "access_token", "api_key")
        forbidden.forEach { assertFalse(it in row.keys, "$it must never be pushed") }
        // device-local fields stay local
        assertFalse("userName" in row.keys || "user_name" in row.keys)
        assertFalse(row.toString().contains("kid"), "the display name of the user is device-local")
    }

    @Test
    fun theAddressRidesTheWireOnlyWhileTheSyncToggleIsOn() {
        val off = MediaServerSyncCodec.pushRow(entry(syncAddress = false), 0)
        assertFalse("base_url" in off.keys, "D7 toggle off: no base_url at all (the server stores NULL)")
        val noAddress = MediaServerSyncCodec.pushRow(entry(address = null), 0)
        assertFalse("base_url" in noAddress.keys)
        assertEquals(JsonPrimitive("http://192.168.1.5:8096"), MediaServerSyncCodec.pushRow(entry(), 0)["base_url"])
    }

    private fun row(
        key: String? = "jellyfin|$m|$u", type: String? = "jellyfin", name: String? = "Home", enabled: Boolean = true,
        baseUrl: String? = "http://nas:8096", url: String? = "jellyfin://$m", username: String? = u,
    ) = MediaServerSyncCodec.RowColumns(key, type, name, enabled, baseUrl, url, username)

    @Test
    fun aPulledJellyfinRowBecomesAnEntry() {
        val e = MediaServerSyncCodec.entryFromRow(row())!!
        assertEquals("jellyfin|$m|$u", e.key)
        assertEquals(MediaServerType.JELLYFIN, e.type)
        assertEquals(m, e.machineId)
        assertEquals(u, e.userId)
        assertEquals("Home", e.name)
        assertEquals("http://nas:8096", e.address)
        assertEquals("jellyfin:$m:$u", e.serverKey)
        assertEquals("jellyfin:$m", e.sourceKey)
    }

    @Test
    fun otherSourceTypesAreNotMediaServerRows() {
        assertNull(MediaServerSyncCodec.entryFromRow(row(type = "xtream")))
        assertNull(MediaServerSyncCodec.entryFromRow(row(type = "m3u_url")))
        assertNull(MediaServerSyncCodec.entryFromRow(row(type = "plex")), "Plex is parked: no code")
        assertNull(MediaServerSyncCodec.entryFromRow(row(type = null)))
    }

    @Test
    fun aReLoginKeepsTheFrozenKeyButAdoptsTheCurrentUser() {
        val e = MediaServerSyncCodec.entryFromRow(row(username = "aaaabbbbccccddddeeeeffff00001111"))!!
        assertEquals("jellyfin|$m|$u", e.key, "key frozen at creation")
        assertEquals("aaaabbbbccccddddeeeeffff00001111", e.userId)
    }

    @Test
    fun aRowWithoutAKeyIsRecoveredFromItsIdentityColumnsOrDropped() {
        val e = MediaServerSyncCodec.entryFromRow(row(key = null))!!
        assertEquals("jellyfin|$m|$u", e.key)
        assertNull(MediaServerSyncCodec.entryFromRow(row(key = null, url = "http://nas:8096")), "an address is not an identity")
        assertNull(MediaServerSyncCodec.entryFromRow(row(key = "emby|$m|$u")), "key type must match source_type")
    }

    @Test
    fun aBlankAddressAndNameFallBack() {
        val e = MediaServerSyncCodec.entryFromRow(row(baseUrl = "  ", name = ""))!!
        assertNull(e.address)
        assertEquals("Jellyfin", e.name)
    }

    @Test
    fun theEmbyRowRoundTrips() {
        val original = entry().copy(key = "emby|$m|$u", type = MediaServerType.EMBY)
        val pushed = MediaServerSyncCodec.pushRow(original, 0)
        val back = MediaServerSyncCodec.entryFromRow(
            MediaServerSyncCodec.RowColumns(
                pushed["playlist_key"]!!.jsonPrimitive.content, pushed["source_type"]!!.jsonPrimitive.content,
                pushed["name"]!!.jsonPrimitive.content, true, pushed["base_url"]?.jsonPrimitive?.content,
                pushed["url"]!!.jsonPrimitive.content, pushed["username"]!!.jsonPrimitive.content,
            ),
        )!!
        assertEquals(original.copy(userName = null, homeRows = emptySet()), back)
    }
}
