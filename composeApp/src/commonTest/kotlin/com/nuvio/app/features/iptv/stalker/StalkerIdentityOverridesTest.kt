package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F46 — the optional Model / Device ID 2 / Signature / HW Version fields.
 *
 * The load-bearing rule: BLANK means exactly what Tuvora sent before the fields existed. Portals pin
 * the first identity they see to the MAC (stock Ministra `getProfile`, Xtream-Codes `lock_device`
 * compares sn / device_id / device_id2 / hw_version), so changing any default would lock out every
 * playlist that already works. Only a value the user typed changes the wire.
 */
class StalkerIdentityOverridesTest {

    private val mac = "00:1A:79:58:B3:A6"

    // --- StalkerProtocol ----------------------------------------------------------------------

    @Test
    fun `blank device id 2 and signature keep the identity we always sent`() {
        val before = StalkerProtocol.deriveDeviceIdentity(mac, serialOverride = "SN1", deviceIdOverride = "DEV1")
        val blank = StalkerProtocol.deriveDeviceIdentity(
            mac, serialOverride = "SN1", deviceIdOverride = "DEV1", deviceId2Override = "  ", signatureOverride = "",
        )
        assertEquals(before, blank)
        assertEquals("DEV1", blank.deviceId2, "device_id2 still mirrors device_id when not entered")
    }

    @Test
    fun `an entered device id 2 is sent verbatim and feeds the derived signature`() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac, deviceIdOverride = "DEV1", deviceId2Override = " DEV2 ")
        assertEquals("DEV1", id.deviceId)
        assertEquals("DEV2", id.deviceId2)
        assertEquals(StalkerProtocol.sha256Hex(mac + id.serialNumber + "DEV1" + "DEV2").uppercase(), id.signature)
    }

    @Test
    fun `an entered signature is sent verbatim`() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac, signatureOverride = " ABCDEF ")
        assertEquals("ABCDEF", id.signature)
        assertEquals(StalkerProtocol.deriveDeviceIdentity(mac).deviceId2, id.deviceId2)
    }

    // --- StalkerMagPresets.pinned -------------------------------------------------------------

    @Test
    fun `no model and no hw version pins nothing`() {
        assertNull(StalkerMagPresets.pinned(null, null))
        assertNull(StalkerMagPresets.pinned("  ", ""))
        assertEquals(StalkerMagPresets.DEFAULT, StalkerMagPresets.initial(null, " "))
    }

    @Test
    fun `a known model takes that box's firmware with the typed model`() {
        val p = StalkerMagPresets.pinned("mag254", null)!!
        assertEquals("mag254", p.stbType, "stb_type is sent as typed")
        assertEquals(StalkerMagPresets.MAG254_STRICT.imageVersion, p.imageVersion)
        assertEquals(StalkerMagPresets.MAG254_STRICT.hwVersion, p.hwVersion)
        assertEquals(StalkerMagPresets.MAG254_STRICT.userAgent, p.userAgent)
        assertEquals("Model: mag254; Link: WiFi", p.xUserAgent)
    }

    @Test
    fun `an unknown model rides the default box`() {
        val p = StalkerMagPresets.pinned("MAG424", null)!!
        assertEquals("MAG424", p.stbType)
        assertEquals(StalkerMagPresets.DEFAULT.imageVersion, p.imageVersion)
        assertEquals(StalkerMagPresets.DEFAULT.hwVersion, p.hwVersion)
        assertEquals("Model: MAG424; Link: WiFi", p.xUserAgent)
    }

    @Test
    fun `a hw version alone keeps the default model`() {
        val p = StalkerMagPresets.pinned(null, " 2.6-IB-00 ")!!
        assertEquals("2.6-IB-00", p.hwVersion)
        assertEquals(StalkerMagPresets.DEFAULT.stbType, p.stbType)
        assertEquals(StalkerMagPresets.DEFAULT.xUserAgent, p.xUserAgent)
    }

    @Test
    fun `a pinned identity is never walked down the ladder`() {
        assertNull(StalkerMagPresets.next(StalkerMagPresets.pinned("MAG254", "2.6-IB-00")))
    }

    // --- StalkerSession: what reaches the portal ----------------------------------------------

    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private var session: StalkerSession? = null

    @AfterTest
    fun tearDown() {
        session?.shutdown()
        session = null
    }

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.replace("+", " ")?.let(::percentDecode)

    private fun percentDecode(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                out.append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3
            } else { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    private fun portal(profileBody: String = """{"js":{"id":"1","status":0}}"""): suspend (String, Map<String, String>) -> String =
        { url, headers ->
            requests += url to headers
            when (param(url, "action")) {
                "handshake" -> """{"js":{"token":"T"}}"""
                "get_profile" -> profileBody
                "get_genres" -> """{"js":[{"id":"1","title":"Sports"}]}"""
                else -> """{"js":[]}"""
            }
        }

    private fun account(
        deviceId2: String? = null, signature: String? = null, stbModel: String? = null, hwVersion: String? = null,
    ) = XtreamAccount(
        id = "f46", name = "portal", baseUrl = "http://portal.test", username = "", password = "",
        sourceType = "stalker", macAddress = mac, deviceId = "DEV1",
        deviceId2 = deviceId2, signature = signature, stbModel = stbModel, hwVersion = hwVersion,
    )

    private fun profileRequest() = requests.first { param(it.first, "action") == "get_profile" }

    @Test
    fun `blank fields put the pre F46 identity on the wire`() = runBlocking {
        session = StalkerSession(account(), portal())
        session!!.request(mapOf("type" to "itv", "action" to "get_genres"))
        val (url, headers) = profileRequest()
        val legacy = StalkerProtocol.deriveDeviceIdentity(mac, deviceIdOverride = "DEV1")
        assertEquals("DEV1", param(url, "device_id2"))
        assertEquals(legacy.signature, param(url, "signature"))
        assertEquals("MAG250", param(url, "stb_type"))
        assertEquals("1.7-BD-00", param(url, "hw_version"))
        assertEquals(StalkerMagPresets.DEFAULT.xUserAgent, headers["X-User-Agent"])
        assertEquals(StalkerMagPresets.DEFAULT.userAgent, headers["User-Agent"])
    }

    @Test
    fun `entered fields reach get_profile and the headers`() = runBlocking {
        session = StalkerSession(
            account(deviceId2 = "DEV2", signature = "SIG", stbModel = "MAG322", hwVersion = "2.6-IB-00"),
            portal(),
        )
        session!!.request(mapOf("type" to "itv", "action" to "get_genres"))
        val (url, headers) = profileRequest()
        assertEquals("DEV1", param(url, "device_id"))
        assertEquals("DEV2", param(url, "device_id2"))
        assertEquals("SIG", param(url, "signature"))
        assertEquals("MAG322", param(url, "stb_type"))
        assertEquals("2.6-IB-00", param(url, "hw_version"))
        assertEquals("Model: MAG322; Link: WiFi", headers["X-User-Agent"])
        // Every portal call presents the same box, not just get_profile.
        val genres = requests.first { param(it.first, "action") == "get_genres" }
        assertEquals("Model: MAG322; Link: WiFi", genres.second["X-User-Agent"])
    }

    @Test
    fun `a rejected pinned identity fails without trying other boxes`() = runBlocking {
        session = StalkerSession(account(stbModel = "MAG254"), portal(profileBody = "Authorization failed."))
        assertFailsWith<StalkerAuthException> {
            session!!.request(mapOf("type" to "itv", "action" to "get_genres"))
        }
        val stbTypes = requests.filter { param(it.first, "action") == "get_profile" }.map { param(it.first, "stb_type") }
        assertTrue(stbTypes.isNotEmpty() && stbTypes.all { it == "MAG254" }, "only the pinned box may be presented: $stbTypes")
    }
}
