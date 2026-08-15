package com.nuvio.app.features.iptv

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sync pull REPLACES the account list with objects rebuilt from the wire, so any field the
 * payload does not carry returns as its constructor default. The catch-up preferences, the learned
 * dialect winner and the guide EPG offset are deliberately not on the wire — they tune ONE panel's
 * behaviour as reached from THIS device — which makes them exactly the fields a pull would silently
 * reset ("my setting keeps un-setting itself": unreportable, and it only appears once a second
 * device exists, so it gets pinned rather than eyeballed).
 */
class XtreamDeviceLocalPrefsSyncTest {

    private fun account(
        id: String,
        preferM3u8: Boolean = false,
        correction: Int = 0,
        guideOffset: Int = 0,
        winner: CatchUpWinner? = null,
    ) = XtreamAccount(
        id = id, name = "Panel", baseUrl = "http://host:8080", username = "u", password = "p",
        catchUpPreferM3u8 = preferM3u8, catchUpTimeCorrectionMinutes = correction,
        guideEpgCorrectionMinutes = guideOffset, catchUpWinner = winner,
    )

    @Test
    fun `a pull keeps this device's panel tunings`() {
        val winner = CatchUpWinner(formatsSignature = "-", dialect = "XUI_PATH_TS", preferM3u8 = false)
        val local = listOf(
            account("http://host:8080|u", preferM3u8 = true, correction = -120, guideOffset = -120, winner = winner)
        )
        // What the wire rebuilds: the shared options, and the device-local fields at their defaults.
        val pulled = listOf(account("http://host:8080|u"))

        val merged = preserveDeviceLocalPrefs(pulled, local).single()
        assertTrue(merged.catchUpPreferM3u8, "container preference kept")
        assertEquals(-120, merged.catchUpTimeCorrectionMinutes, "time correction kept")
        assertEquals(-120, merged.guideEpgCorrectionMinutes, "guide offset kept")
        assertEquals(winner, merged.catchUpWinner, "learned dialect kept")
    }

    /** The rest of the pulled account still wins — this preserves four fields, not the whole object. */
    @Test
    fun `everything else still comes from the pull`() {
        val local = listOf(
            account("http://host:8080|u", preferM3u8 = true).copy(name = "Old name", enabled = false)
        )
        val pulled = listOf(account("http://host:8080|u").copy(name = "New name", enabled = true))

        val merged = preserveDeviceLocalPrefs(pulled, local).single()
        assertEquals("New name", merged.name, "remote name wins")
        assertTrue(merged.enabled, "remote enabled wins")
        assertTrue(merged.catchUpPreferM3u8, "but the local preference survives")
    }

    /** A playlist added on another device has no local preferences to keep. */
    @Test
    fun `an account this device has never seen takes the defaults`() {
        val merged = preserveDeviceLocalPrefs(listOf(account("http://other|u")), emptyList()).single()
        assertFalse(merged.catchUpPreferM3u8)
        assertEquals(0, merged.catchUpTimeCorrectionMinutes)
        assertEquals(0, merged.guideEpgCorrectionMinutes)
        assertNull(merged.catchUpWinner)
    }

    /** Matching is by id, so one playlist's preference can never leak onto another's. */
    @Test
    fun `preferences do not leak between playlists`() {
        val local = listOf(account("a", guideOffset = 60), account("b"))
        val merged = preserveDeviceLocalPrefs(listOf(account("a"), account("b")), local)
        assertEquals(60, merged.first { it.id == "a" }.guideEpgCorrectionMinutes)
        assertEquals(0, merged.first { it.id == "b" }.guideEpgCorrectionMinutes)
    }

    /**
     * Every stored playlist predates the guide offset, so the missing-field case is the ONLY case
     * on first run after an update — and 0 must read as "auto-detect", not as a stored "+0"
     * override that suppresses it.
     */
    @Test
    fun `json written before the guide offset shipped decodes to auto`() {
        val json = Json { ignoreUnknownKeys = true }
        val acc = json.decodeFromString<XtreamAccount>(
            """{"id":"http://h|u","name":"Panel","baseUrl":"http://h","username":"u","password":"p"}"""
        )
        assertEquals(0, acc.guideEpgCorrectionMinutes, "auto by default")
        assertNull(acc.guideEpgCorrectionMs(), "0 means auto, not a +0 override")
        assertEquals(
            -120 * 60_000L,
            acc.copy(guideEpgCorrectionMinutes = -120).guideEpgCorrectionMs(),
            "a stored offset converts to milliseconds",
        )
    }
}
