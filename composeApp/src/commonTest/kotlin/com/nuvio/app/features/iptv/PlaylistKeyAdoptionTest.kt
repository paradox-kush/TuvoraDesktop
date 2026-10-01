package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/** Step 0 — the pure "which local ids must be re-keyed to which pulled keys" decision. */
class PlaylistKeyAdoptionTest {

    private fun xtream(id: String, base: String, user: String = "u") =
        XtreamAccount(id = id, name = "P", baseUrl = base, username = user, password = "p")

    private fun m3u(id: String, url: String) =
        XtreamAccount(id = id, name = "M", baseUrl = url, username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL)

    private fun file(id: String, fileName: String) =
        XtreamAccount(id = id, name = "F", baseUrl = "", username = "", password = "", sourceType = SOURCE_TYPE_M3U_FILE, fileName = fileName)

    private fun keyed(a: XtreamAccount) = PulledPlaylist(a, serverKeyed = true)
    private fun derived(a: XtreamAccount) = PulledPlaylist(a, serverKeyed = false)

    @Test
    fun `a pulled key that differs from the local id of the same playlist re-keys the local id`() {
        // TV's old `m3u:` id vs the server's `m3u|` key for the same link.
        val local = m3u("m3u:http://h/list.m3u", "http://h/list.m3u?u=1")
        val pulled = m3u("m3u|http://h/list.m3u?u=1", "http://h/list.m3u?u=1")
        val result = PlaylistKeyAdoption.resolve(listOf(keyed(pulled)), listOf(local))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("m3u:http://h/list.m3u", "m3u|http://h/list.m3u?u=1")), result.rekeys)
        assertEquals(listOf("m3u|http://h/list.m3u?u=1"), result.accounts.map { it.id })
    }

    @Test
    fun `a rescued orphan key moves the current-address id onto the old key`() {
        // The server kept the playlist's pre-domain-change id (where the user's data lives).
        val local = xtream("http://new.example|u", "http://new.example")
        val pulled = xtream("http://old.example|u", "http://new.example")
        val result = PlaylistKeyAdoption.resolve(listOf(keyed(pulled)), listOf(local))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("http://new.example|u", "http://old.example|u")), result.rekeys)
    }

    @Test
    fun `a file playlist adopts the server key and the local copy must follow`() {
        val local = file("m3u_file|tv.m3u|1719000000000", "tv.m3u")
        val pulled = file("m3u_file|tv.m3u|synced", "tv.m3u")
        val result = PlaylistKeyAdoption.resolve(listOf(keyed(pulled)), listOf(local))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("m3u_file|tv.m3u|1719000000000", "m3u_file|tv.m3u|synced")), result.rekeys)
    }

    @Test
    fun `a row without a server key keeps the local id instead of re-deriving it from the address`() {
        // Un-migrated server: the derived id is the NEW address, the local id is frozen at the old one.
        val local = xtream("http://old.example|u", "http://new.example")
        val pulled = xtream("http://new.example|u", "http://new.example")
        val result = PlaylistKeyAdoption.resolve(listOf(derived(pulled)), listOf(local))
        assertEquals(emptyList(), result.rekeys, "no data moves for an un-keyed row")
        assertEquals(listOf("http://old.example|u"), result.accounts.map { it.id })
    }

    @Test
    fun `adoption is idempotent - the next pull re-keys nothing`() {
        val local = m3u("m3u:http://h/a.m3u", "http://h/a.m3u")
        val pulled = listOf(keyed(m3u("m3u|http://h/a.m3u", "http://h/a.m3u")))
        val first = PlaylistKeyAdoption.resolve(pulled, listOf(local))
        assertEquals(1, first.rekeys.size)
        val second = PlaylistKeyAdoption.resolve(pulled, first.accounts)
        assertEquals(emptyList(), second.rekeys)
        assertEquals(first.accounts, second.accounts)
    }

    @Test
    fun `matching ids and different playlists never re-key`() {
        val same = xtream("k1", "http://a.example")
        val other = xtream("k2", "http://b.example")
        val result = PlaylistKeyAdoption.resolve(
            listOf(keyed(xtream("k1", "http://a.example")), keyed(xtream("k3", "http://c.example"))),
            listOf(same, other),
        )
        assertEquals(emptyList(), result.rekeys)
    }

    @Test
    fun `a local id the pull still lists is never re-keyed onto another row`() {
        // Two server rows at one address: k1 (matches local exactly) and k2. Local k1 must stay k1.
        val local = xtream("k1", "http://a.example")
        val result = PlaylistKeyAdoption.resolve(
            listOf(keyed(xtream("k2", "http://a.example")), keyed(xtream("k1", "http://a.example"))),
            listOf(local),
        )
        assertEquals(emptyList(), result.rekeys)
    }

    @Test
    fun `matching is one to one and needs the same source type`() {
        val a = xtream("old-a", "http://a.example")
        val b = xtream("old-b", "http://a.example")
        val result = PlaylistKeyAdoption.resolve(listOf(keyed(xtream("key", "http://A.example:80/"))), listOf(a, b))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("old-a", "key")), result.rekeys)
        val m3uAtSameUrl = m3u("m3u-old", "http://a.example")
        assertEquals(
            emptyList(),
            PlaylistKeyAdoption.resolve(listOf(keyed(xtream("key", "http://a.example"))), listOf(m3uAtSameUrl)).rekeys,
        )
    }

    @Test
    fun `pending ops are rewritten onto the adopted key`() {
        val edited = xtream("old", "http://a.example")
        val pending = listOf(
            PendingOpDto("update", "old", edited, base = edited),
            PendingOpDto("replace", "x2", xtream("x2", "http://b.example"), oldId = "old"),
            PendingOpDto("delete", "untouched"),
        )
        val out = PlaylistKeyAdoption.rewritePending(pending, listOf(PlaylistKeyAdoption.Rekey("old", "new")))
        assertEquals(listOf("new", "x2", "untouched"), out.map { it.id })
        assertEquals("new", out[0].account?.id)
        assertEquals("new", out[0].base?.id)
        assertEquals("new", out[1].oldId)
    }
}
