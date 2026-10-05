package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B64 device pass T1: the re-key ran only after an ingest / a profile load, so an old-id row that
 * reached the device later (a remote pull racing the re-key's delete, a delete that never went out, a
 * not-yet-updated phone re-pushing it) stayed as a second Continue Watching card that opened an empty
 * page. The runner now re-runs whenever the saved ids change; this ledger keeps that free when nothing
 * new arrived (no catalog read) and makes a re-appearing old id get checked again.
 */
class M3uIdRekeyLedgerTest {

    private val p = "m3u|http://h/get.php|u00000000"
    private val old = "xtream:$p:vod:1"
    private val new = "xtream:$p:vod:2"
    private val unknown = "xtream:$p:vod:3"
    private val plan = M3uIdRekey.Plan(renames = mapOf(old to new), promoted = emptyMap())

    @Test
    fun afterARekeyTheSameSavedIdsNeedNoCatalogRead() {
        val ledger = M3uIdRekeyLedger()
        assertEquals(setOf(old, unknown), ledger.toCheck(p, setOf(old, unknown)))
        ledger.settle(p, setOf(old, unknown), plan)
        assertTrue(ledger.toCheck(p, setOf(new, unknown)).isEmpty(), "nothing new saved: the sweep reads no catalog")
    }

    @Test
    fun anOldIdPulledBackAfterTheRekeyIsCheckedAgain() {
        val ledger = M3uIdRekeyLedger()
        ledger.settle(p, setOf(old), plan)
        assertEquals(setOf(old), ledger.toCheck(p, setOf(new, old)))
    }

    @Test
    fun aRebuiltCatalogChecksEverythingAgain() {
        val ledger = M3uIdRekeyLedger()
        ledger.settle(p, setOf(old, unknown), plan)
        ledger.forget(p)
        assertEquals(setOf(new, unknown), ledger.toCheck(p, setOf(new, unknown)))
    }

    @Test
    fun playlistsAreTrackedApart() {
        val ledger = M3uIdRekeyLedger()
        ledger.settle(p, setOf(old), plan)
        assertEquals(setOf(new), ledger.toCheck("m3u|other", setOf(new)))
    }
}
