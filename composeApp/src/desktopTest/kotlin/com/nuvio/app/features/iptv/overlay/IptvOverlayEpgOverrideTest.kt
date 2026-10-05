package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F14 manual guide picks ride the overlay's delta sync as kind "epg": dirty rows only, cleared on
 * ack, LWW on pull, purged with the playlist. Lane G — additive to lane B's overlay store.
 */
class IptvOverlayEpgOverrideTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("overlay_epg", ".db").also { it.delete() }
        OverlayDbDriver.openForTests = { BundledSQLiteDriver().open(dbFile.absolutePath) }
        runBlocking { IptvOverlayStore.closeForTests() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { IptvOverlayStore.closeForTests() }
        OverlayDbDriver.openForTests = null
        dbFile.delete()
    }

    @Test
    fun `a pick is pushed once as a delta row and cleared on ack`() = runBlocking {
        IptvOverlayStore.setEpgOverride(1, "fp:v1:a", "pl", "itv1.uk", "ITV1", 100)
        val rows = IptvOverlayStore.rowsForPush(1).filter { it.kind == IptvOverlayStore.EPG_KIND }
        assertEquals(1, rows.size)
        assertEquals("{\"guide_id\":\"itv1.uk\",\"guide_name\":\"ITV1\"}", rows.single().valueJson)
        assertEquals(false, rows.single().deleted)
        IptvOverlayStore.markChannelsPushed(1, rows)
        assertTrue(IptvOverlayStore.rowsForPush(1).none { it.kind == IptvOverlayStore.EPG_KIND }, "acked rows are not re-sent")
        assertEquals(mapOf("fp:v1:a" to "itv1.uk"), IptvOverlayStore.epgOverrides(1, "pl"))
    }

    @Test
    fun `clearing a pick pushes a delete and drops it from reads`() = runBlocking {
        IptvOverlayStore.setEpgOverride(1, "fp:v1:a", "pl", "itv1.uk", "ITV1", 100)
        IptvOverlayStore.setEpgOverride(1, "fp:v1:a", "pl", null, null, 200)
        assertEquals(true, IptvOverlayStore.rowsForPush(1).single { it.kind == IptvOverlayStore.EPG_KIND }.deleted)
        assertEquals(emptyMap(), IptvOverlayStore.epgOverrides(1, "pl"))
    }

    @Test
    fun `a stale pulled pick never overwrites a newer local one`() = runBlocking {
        IptvOverlayStore.setEpgOverride(1, "fp:v1:a", "pl", "new.uk", null, 300)
        IptvOverlayStore.applyRemoteEpg(1, "fp:v1:a", "pl", "old.uk", null, 200, deleted = false)
        assertEquals(mapOf("fp:v1:a" to "new.uk"), IptvOverlayStore.epgOverrides(1, "pl"))
        IptvOverlayStore.applyRemoteEpg(1, "fp:v1:a", "pl", "web.uk", null, 400, deleted = false)
        assertEquals(mapOf("fp:v1:a" to "web.uk"), IptvOverlayStore.epgOverrides(1, "pl"))
    }

    @Test
    fun `picks are per profile but the ingest keeps every profile's channel`() = runBlocking {
        IptvOverlayStore.setEpgOverride(1, "fp:v1:a", "pl", "a.uk", null, 100)
        IptvOverlayStore.setEpgOverride(2, "fp:v1:a", "pl", "b.uk", null, 100)
        assertEquals(mapOf("fp:v1:a" to "a.uk"), IptvOverlayStore.epgOverrides(1, "pl"))
        assertEquals(setOf("a.uk", "b.uk"), IptvOverlayStore.epgOverrideGuideIds("pl"))
        IptvOverlayStore.purgePlaylist(1, "pl")
        assertEquals(setOf("b.uk"), IptvOverlayStore.epgOverrideGuideIds("pl"))
    }
}
