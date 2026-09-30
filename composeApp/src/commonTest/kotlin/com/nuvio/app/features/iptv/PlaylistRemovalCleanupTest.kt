package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.PlaylistRemovalOrigin.SyncPull
import com.nuvio.app.features.iptv.PlaylistRemovalOrigin.UserDelete
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.CatchUp
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.ContentDb
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.EpgMirror
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.HubSelection
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.LiveChannels
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.M3uFileCopy
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.MatchIndex
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.Overlay
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.RefreshStamp
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.SavedRefs
import com.nuvio.app.features.iptv.PlaylistRemovalTarget.SessionCaches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure "what to purge for a removed playlist" plan. Twin of NuvioTV's PlaylistRemovalCleanupTest. */
class PlaylistRemovalCleanupTest {

    private val caches = setOf(ContentDb, MatchIndex, EpgMirror, RefreshStamp, M3uFileCopy, CatchUp, SessionCaches, HubSelection)
    private val userData = setOf(Overlay, LiveChannels, SavedRefs)

    @Test
    fun `a user delete clears every store keyed by the playlist`() {
        assertEquals(PlaylistRemovalTarget.entries.toSet(), PlaylistRemovalCleanup.plan(UserDelete))
    }

    @Test
    fun `a sync pull clears every cache but no user data`() {
        val plan = PlaylistRemovalCleanup.plan(SyncPull)
        assertEquals(caches, plan)
        assertTrue(plan.none { it.userData }, "a transient pull must never drop user data")
    }

    @Test
    fun `the cache and user-data split is exhaustive`() {
        assertEquals(PlaylistRemovalTarget.entries.toSet(), caches + userData)
        assertTrue(userData.all { it.userData })
        assertTrue(caches.none { it.userData })
    }

    @Test
    fun `the content db is purged whatever the source type`() {
        // Xtream playlists fill the per-playlist EPG tables too (xmltv store lane + catch-up refills),
        // so the content DB clear is not an M3U-only step.
        assertTrue(ContentDb in PlaylistRemovalCleanup.plan(UserDelete))
        assertTrue(ContentDb in PlaylistRemovalCleanup.plan(SyncPull))
    }

    @Test
    fun `removed ids are the ones the new list no longer carries`() {
        assertEquals(listOf("a", "c"), PlaylistRemovalCleanup.removedIds(listOf("a", "b", "c"), listOf("b", "d")))
        assertEquals(emptyList<String>(), PlaylistRemovalCleanup.removedIds(listOf("a"), listOf("a")))
        assertEquals(emptyList<String>(), PlaylistRemovalCleanup.removedIds(emptyList(), listOf("a")))
        assertEquals(listOf("a"), PlaylistRemovalCleanup.removedIds(listOf("a", "a"), emptyList()))
    }

    @Test
    fun `the hub forgets a remembered selection only when it names the removed playlist`() {
        assertTrue(PlaylistRemovalCleanup.dropsHubSelection("a", "a"))
        assertFalse(PlaylistRemovalCleanup.dropsHubSelection("b", "a"))
        assertFalse(PlaylistRemovalCleanup.dropsHubSelection(null, "a"))
    }

    @Test
    fun `a refresh stamp map loses only the removed playlist`() {
        assertEquals(mapOf("b" to 2L), PlaylistRemovalCleanup.withoutPlaylist(mapOf("a" to 1L, "b" to 2L), "a"))
        val untouched = mapOf("b" to 2L)
        assertSame(untouched, PlaylistRemovalCleanup.withoutPlaylist(untouched, "a"))
    }
}
