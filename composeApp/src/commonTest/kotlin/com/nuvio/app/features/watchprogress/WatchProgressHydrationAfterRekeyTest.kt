package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B64 parity of NuvioTV T1 (Continue Watching showed one item twice after the re-key because artwork
 * hydration wrote the pre-re-key entry back). Metadata hydration snapshots the entries, fetches, then
 * applies; a re-key in between must not be undone. Hydration may only PATCH an entry still stored under
 * the key it captured, and must patch the CURRENT entry (so a fresher position is never rolled back).
 */
class WatchProgressHydrationAfterRekeyTest {

    private val oldVod = "xtream:m3u|http://h/get.php|u00000000:vod:77"
    private val newMovie = "xtream:m3u|http://h/get.php|u00000000:vod:ab"
    private val meta = MetaDetails(id = oldVod, type = "movie", name = "Heat", poster = "https://img/heat.jpg")

    private fun movie(id: String, position: Long, updated: Long, title: String = "") = WatchProgressEntry(
        contentType = "movie", parentMetaId = id, parentMetaType = "movie", videoId = id, title = title,
        lastPositionMs = position, durationMs = 1_000_000, lastUpdatedEpochMs = updated,
    ).withResolvedProgressKey()

    private fun store(vararg entries: WatchProgressEntry): MutableMap<String, WatchProgressEntry> =
        entries.associateByTo(mutableMapOf()) { it.resolvedProgressKey() }

    @Test
    fun hydrationStartedBeforeTheRekeyNeverBringsTheOldKeyBack() {
        val stale = movie(oldVod, position = 100_000, updated = 1_000) // what hydration's snapshot held
        val local = store(stale)
        // The re-key lands between the snapshot and the hydration result.
        val plan = WatchProgressRekey.plan(local.values) { e ->
            e.copy(parentMetaId = newMovie, videoId = newMovie, progressKey = null)
        }
        plan.removed.forEach { local.remove(it.resolvedProgressKey()) }
        plan.upserts.forEach { local[it.resolvedProgressKey()] = it }
        val before = local.toMap()

        val applied = local.patchExistingWithMetadata(stale.resolvedProgressKey(), meta)

        assertFalse(applied, "the entry moved away: nothing to patch")
        assertEquals(before, local, "the store is untouched: exactly one entry, under the new key")
        assertEquals(listOf(newMovie), local.keys.toList())
    }

    @Test
    fun hydrationPatchesTheCurrentEntryAndKeepsItsFresherProgress() {
        val snapshot = movie(oldVod, position = 100_000, updated = 1_000)
        val local = store(movie(oldVod, position = 900_000, updated = 5_000)) // playback saved meanwhile

        assertTrue(local.patchExistingWithMetadata(snapshot.resolvedProgressKey(), meta))

        val patched = local.getValue(oldVod)
        assertEquals(900_000L, patched.lastPositionMs, "a stale snapshot must not roll the position back")
        assertEquals(5_000L, patched.lastUpdatedEpochMs)
        assertEquals("Heat", patched.title)
        assertEquals("https://img/heat.jpg", patched.poster)
    }

    @Test
    fun hydrationOfAnEntryRemovedMeanwhileCreatesNothing() {
        val local = store()
        assertFalse(local.patchExistingWithMetadata(oldVod, meta))
        assertTrue(local.isEmpty())
    }
}
