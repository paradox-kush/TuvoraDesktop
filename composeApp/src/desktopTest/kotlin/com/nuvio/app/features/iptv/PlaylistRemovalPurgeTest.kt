package com.nuvio.app.features.iptv

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.epg.EpgMappingRow
import com.nuvio.app.features.epg.EpgMirrorDb
import com.nuvio.app.features.epg.EpgMirrorDbDriver
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.content.IptvContentDbDriver
import com.nuvio.app.features.iptv.match.IndexedItem
import com.nuvio.app.features.iptv.match.MatchDbDriver
import com.nuvio.app.features.iptv.match.MatchKind
import com.nuvio.app.features.iptv.match.XtreamMatchIndex
import com.nuvio.app.features.iptv.overlay.CategoryOverlay
import com.nuvio.app.features.iptv.overlay.ChannelOverlay
import com.nuvio.app.features.iptv.overlay.IptvOverlayStore
import com.nuvio.app.features.iptv.overlay.OverlayDbDriver
import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Regression: removing a playlist left its on-device data behind.
 *
 * Real-SQLite round trip through the two paths a playlist leaves by — the user deleting it
 * ([XtreamRepository.remove]) and a sync pull that no longer lists it ([XtreamRepository.applyFromRemote]).
 * Seeds every per-playlist store for a doomed playlist AND a surviving one, then asserts the doomed
 * one's rows are gone and the survivor's are untouched.
 *
 * Red on the old code: an XTREAM playlist's guide rows (xmltv store lane + catch-up refills live in
 * the same per-playlist EPG tables) were only cleared for M3U/Stalker; the EPG-mirror schedule meta
 * was never dropped; and a sync-pull removal skipped the content DB entirely.
 */
class PlaylistRemovalPurgeTest {

    private fun account(tag: String) = XtreamAccount(
        id = "http://$tag.example.com:8080|u",
        name = tag,
        baseUrl = "http://$tag.example.com:8080",
        username = "u",
        password = "p",
    )

    private fun programme(start: Long) = EpgProgrammeRow("bbc.uk", start, start + 1_000L, "Show $start", null)

    @BeforeTest
    fun setUp() {
        IptvContentDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        MatchDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        EpgMirrorDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
        OverlayDbDriver.openForTests = { BundledSQLiteDriver().open(":memory:") }
    }

    @AfterTest
    fun tearDown() {
        XtreamRepository.installAccountsForTest(emptyList())
    }

    private suspend fun seed(acc: XtreamAccount) {
        val id = acc.id
        // Guide: a whole-guide refresh (epg_meta + epg_programmes) and a per-channel lazy refill
        // (epg_channel_fetch) — both land for Xtream as well as M3U/Stalker.
        IptvContentDb.beginEpg(id)
        IptvContentDb.insertEpgChunk(id, listOf(programme(1_000L)))
        IptvContentDb.finishEpg(id, 1)
        IptvContentDb.refillChannelEpg(id, "bbc.uk", listOf(programme(5_000L)), fetchedAtMs = 42L)
        // A refresh that was in flight when the playlist went (shadow generation).
        IptvContentDb.beginEpg(id)
        IptvContentDb.insertEpgChunk(id, listOf(programme(9_000L)))
        // TMDB match index.
        XtreamMatchIndex.rebuild(
            id, MatchKind.LIVE,
            listOf(IndexedItem(sid = 1, name = "BBC ONE", year = null, tmdb = null, ext = null, poster = null, categoryId = "uk", epgId = "bbc.uk", hasArchive = false)),
        )
        // Canonical-EPG mirror mapping + its schedule meta.
        EpgMirrorDb.replaceMapping(id, listOf(EpgMappingRow(1, "BBCOne.uk", "exact")))
        EpgMirrorDb.setMeta("acct_attempt_ms:$id", "123")
        // Personalization overlay (user data).
        val profile = ProfileRepository.activeProfileId
        IptvOverlayStore.setChannel(profile, "chan:$id", id, ChannelOverlay(hidden = true), 1L)
        IptvOverlayStore.setCategory(profile, id, "live", "cat:$id", CategoryOverlay(hidden = true), 1L)
    }

    private suspend fun hasGuide(id: String) =
        IptvContentDb.epgWindow(id, "bbc.uk", fromMs = 0L, toMs = 100_000L).isNotEmpty()

    private suspend fun cachesGone(id: String): Boolean =
        !hasGuide(id) &&
            IptvContentDb.epgMeta(id) == null &&
            IptvContentDb.epgChannelFetchedAt(id, "bbc.uk") == null &&
            XtreamMatchIndex.builtAt(id, MatchKind.LIVE) == null &&
            EpgMirrorDb.mappingFor(id).isEmpty() &&
            EpgMirrorDb.meta("acct_attempt_ms:$id") == null

    private suspend fun assertCachesIntact(id: String) {
        assertTrue(hasGuide(id), "survivor keeps its guide rows")
        assertNotNull(IptvContentDb.epgMeta(id), "survivor keeps its epg_meta")
        assertEquals(42L, IptvContentDb.epgChannelFetchedAt(id, "bbc.uk"), "survivor keeps its fetch stamp")
        assertNotNull(XtreamMatchIndex.builtAt(id, MatchKind.LIVE), "survivor keeps its match index")
        assertEquals(1, EpgMirrorDb.mappingFor(id).size, "survivor keeps its mirror mapping")
        assertEquals("123", EpgMirrorDb.meta("acct_attempt_ms:$id"), "survivor keeps its mirror meta")
    }

    private suspend fun overlayHas(id: String): Boolean {
        val snap = IptvOverlayStore.snapshot(ProfileRepository.activeProfileId)
        return "chan:$id" in snap.channels && "cat:$id" in snap.categories
    }

    /**
     * An EPG refresh that was in flight when the playlist went leaves rows in the shadow table.
     * Probe without a test seam: completing that refresh swaps whatever shadow survived into the
     * live guide — after a full purge there is nothing to swap in.
     */
    private suspend fun inFlightGuideSurvived(id: String): Boolean {
        IptvContentDb.finishEpg(id, 1)
        return hasGuide(id)
    }

    /** The purge runs on the repository's background scope — poll until it lands (or give up). */
    private suspend fun eventually(condition: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(3_000L) {
            while (!condition()) delay(20L)
            true
        } ?: false

    @Test
    fun `deleting an xtream playlist purges its on-device stores and spares the others`() = runBlocking {
        val doomed = account("del-doomed")
        val keeper = account("del-keeper")
        seed(doomed); seed(keeper)
        XtreamRepository.installAccountsForTest(listOf(doomed, keeper))

        XtreamRepository.remove(doomed.id)

        assertTrue(eventually { cachesGone(doomed.id) }, "every cache keyed by the deleted playlist is purged")
        assertTrue(eventually { !overlayHas(doomed.id) }, "an explicit delete drops the playlist's overlay")
        assertFalse(inFlightGuideSurvived(doomed.id), "the in-flight EPG shadow rows are purged too")
        assertCachesIntact(keeper.id)
        assertTrue(overlayHas(keeper.id), "survivor keeps its overlay")
    }

    @Test
    fun `a playlist dropped by a sync pull purges its caches but keeps user data`() = runBlocking {
        val doomed = account("pull-doomed")
        val keeper = account("pull-keeper")
        seed(doomed); seed(keeper)
        XtreamRepository.installAccountsForTest(listOf(doomed, keeper))

        XtreamRepository.applyFromRemote(ProfileRepository.activeProfileId, listOf(keeper))

        assertTrue(eventually { cachesGone(doomed.id) }, "a pull removal runs the same cache purge as a delete")
        assertFalse(inFlightGuideSurvived(doomed.id), "the in-flight EPG shadow rows are purged too")
        assertCachesIntact(keeper.id)
        // A pull can be transient (B24): synced user data is never dropped on its say-so.
        assertTrue(overlayHas(doomed.id), "a pull removal leaves the overlay (user data) alone")
    }
}
