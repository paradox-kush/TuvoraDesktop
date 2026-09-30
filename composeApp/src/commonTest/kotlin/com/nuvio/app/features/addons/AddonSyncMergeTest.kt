package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonSyncMergeTest {

    private val torrentio = "https://torrentio.test/config/manifest.json"
    private val cinemeta = "https://cinemeta.test/manifest.json"
    private val subs = "https://subs.test/manifest.json"

    @Test
    fun `addons added on a device that never synced survive an empty server and are pushed`() {
        // The field report: addons installed on desktop never reached the server, so the pull
        // saw an empty server list and replaced the device's addons with nothing.
        val outcome = AddonSyncMerge.merge(
            local = listOf(torrentio, cinemeta),
            remote = emptyList(),
            lastSynced = null,
        )

        assertEquals(listOf(torrentio, cinemeta), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun `a never synced device keeps the account addons and appends its own`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(subs, torrentio),
            remote = listOf(cinemeta, torrentio),
            lastSynced = null,
        )

        assertEquals(listOf(cinemeta, torrentio, subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun `a never synced device that already matches the server pushes nothing`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta),
            remote = listOf(cinemeta, torrentio),
            lastSynced = null,
        )

        assertEquals(listOf(cinemeta, torrentio), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun `with no local edits the server list wins even when empty`() {
        // Deleting every addon on another device must stick here too.
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = emptyList(),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(emptyList(), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun `with no local edits server additions and order are taken as is`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = listOf(torrentio, subs, cinemeta),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(torrentio, subs, cinemeta), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun `an addon added locally since the last sync is appended and pushed`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, subs),
            remote = listOf(cinemeta, torrentio),
            lastSynced = listOf(cinemeta),
        )

        assertEquals(listOf(cinemeta, torrentio, subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun `an addon removed locally since the last sync is dropped from the server list and pushed`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta),
            remote = listOf(cinemeta, torrentio),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(cinemeta), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun `an addon another device removed is not resurrected from the last synced list`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = listOf(cinemeta),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(cinemeta), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun `urls are compared by key so the same addon is never duplicated`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf("HTTPS://TORRENTIO.TEST/config/manifest.json"),
            remote = listOf(torrentio),
            lastSynced = null,
            key = { it.lowercase() },
        )

        assertEquals(listOf(torrentio), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun `duplicate local entries are collapsed`() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(subs, subs),
            remote = emptyList(),
            lastSynced = null,
        )

        assertEquals(listOf(subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }
}
