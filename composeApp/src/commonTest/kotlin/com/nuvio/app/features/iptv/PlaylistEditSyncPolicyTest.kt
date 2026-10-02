package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B60 / B04 — the pure decisions around a playlist edit: whether a failed provider check may block
 * the save (decision 2026-09-27: it may not — save and warn), whether an option edit must be synced,
 * and that a v2 apply carries this device's local-only preferences across.
 */
class PlaylistEditSyncPolicyTest {

    private fun acc(id: String = "A") =
        XtreamAccount(id = id, name = "P", baseUrl = "http://$id", username = "u", password = "p")

    @Test
    fun `an options-only edit needs no provider check`() {
        val old = acc()
        assertFalse(PlaylistEditVerifyPolicy.needsVerify(old, old.copy(name = "Renamed", autoRefreshHours = 6)))
    }

    @Test
    fun `a URL edit needs a provider check`() {
        val old = acc()
        assertTrue(PlaylistEditVerifyPolicy.needsVerify(old, old.copy(id = "http://B|u", baseUrl = "http://B")))
    }

    @Test
    fun `a failed provider check on a URL edit still saves - with a warning`() {
        val outcome = PlaylistEditVerifyPolicy.outcome(Result.failure(RuntimeException("Could not reach the panel")), SOURCE_TYPE_XTREAM)
        assertTrue(outcome.save, "what the user typed is never discarded")
        val failure = assertNotNull(outcome.failure, "the failure is surfaced, not swallowed")
        assertEquals(PlaylistSaveMessage.Known(PlaylistSaveError.UNREACHABLE), failure, "the reason is mapped, not raw (UX11)")
    }

    @Test
    fun `a passed provider check saves with no warning`() {
        val outcome = PlaylistEditVerifyPolicy.outcome(Result.success(Unit), SOURCE_TYPE_XTREAM)
        assertTrue(outcome.save)
        assertNull(outcome.failure)
    }

    @Test
    fun `a content-type edit is a synced change`() {
        val old = acc()
        assertTrue(optionEditNeedsSync(old, old.copy(contentTypes = setOf("live"))))
    }

    @Test
    fun `a device-local catch-up preference edit is not a synced change`() {
        val old = acc()
        assertFalse(optionEditNeedsSync(old, old.copy(catchUpPreferM3u8 = true, guideEpgCorrectionMinutes = 60)))
    }

    @Test
    fun `a v2 apply keeps this device's local-only preferences`() {
        val local = listOf(acc().copy(catchUpPreferM3u8 = true, catchUpTimeCorrectionMinutes = 30, guideEpgCorrectionMinutes = -60))
        val pulled = listOf(acc().copy(userAgent = "X"))
        val applied = v2ApplyLocal(pulled, local).single()
        assertEquals("X", applied.userAgent, "the synced field comes from the server")
        assertTrue(applied.catchUpPreferM3u8, "the device-local catch-up preference survives")
        assertEquals(30, applied.catchUpTimeCorrectionMinutes)
        assertEquals(-60, applied.guideEpgCorrectionMinutes)
    }
}
