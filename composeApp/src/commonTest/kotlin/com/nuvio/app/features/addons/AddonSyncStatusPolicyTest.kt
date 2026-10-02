package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonSyncStatusPolicyTest {
    private val a = "https://a.example/manifest.json"
    private val b = "https://b.example/manifest.json"

    private fun status(
        local: List<String>,
        lastSynced: List<String>?,
        hasAccount: Boolean = true,
        followsPrimaryProfile: Boolean = false,
        syncInFlight: Boolean = false,
    ) = AddonSyncStatusPolicy.status(local, lastSynced, hasAccount, followsPrimaryProfile, syncInFlight)

    @Test
    fun `list matching the last synced baseline is synced`() {
        assertEquals(AddonSyncStatus.Synced, status(listOf(a, b), listOf(a, b)))
    }

    @Test
    fun `addon added after the last sync is not synced`() {
        assertEquals(AddonSyncStatus.NotSynced, status(listOf(a, b), listOf(a)))
    }

    @Test
    fun `addon removed after the last sync is not synced`() {
        assertEquals(AddonSyncStatus.NotSynced, status(listOf(a), listOf(a, b)))
    }

    @Test
    fun `unpushed reorder is not synced`() {
        assertEquals(AddonSyncStatus.NotSynced, status(listOf(b, a), listOf(a, b)))
    }

    @Test
    fun `device that never synced with addons installed is not synced`() {
        assertEquals(AddonSyncStatus.NotSynced, status(listOf(a), null))
    }

    @Test
    fun `device that never synced with no addons has nothing to say`() {
        assertEquals(AddonSyncStatus.Synced, status(emptyList(), null))
    }

    @Test
    fun `no account means no marker`() {
        assertEquals(AddonSyncStatus.NotApplicable, status(listOf(a), null, hasAccount = false))
    }

    @Test
    fun `profile using the primary addons gets no marker`() {
        assertEquals(AddonSyncStatus.NotApplicable, status(listOf(a), listOf(b), followsPrimaryProfile = true))
    }

    @Test
    fun `push in flight hides the marker instead of flashing it`() {
        assertEquals(AddonSyncStatus.Syncing, status(listOf(a, b), listOf(a), syncInFlight = true))
    }

    @Test
    fun `same addon under another url spelling is synced`() {
        val result = AddonSyncStatusPolicy.status(
            local = listOf("https://A.example/manifest.json"),
            lastSynced = listOf(a),
            hasAccount = true,
            followsPrimaryProfile = false,
            syncInFlight = false,
            key = { it.lowercase() },
        )
        assertEquals(AddonSyncStatus.Synced, result)
    }
}
