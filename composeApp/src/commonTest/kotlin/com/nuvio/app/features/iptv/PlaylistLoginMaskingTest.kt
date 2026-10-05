package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4/T7 — a playlist's login must never be DISPLAYED: lists and pickers showed an M3U link's
 * `username=…&password=…` in plain text, both as the row's address line and as the NAME a nameless
 * synced row was given (that name then appears in every list, picker and dropdown, and syncs).
 */
class PlaylistLoginMaskingTest {

    private val m3u = "http://host.example:8080/get.php?username=alice&password=s3cret&type=m3u_plus"

    private fun assertNoLogin(shown: String, what: String) {
        assertFalse("alice" in shown, "$what shows the username: $shown")
        assertFalse("s3cret" in shown, "$what shows the password: $shown")
    }

    @Test
    fun `a masked M3U address keeps its host and shape but not the login`() {
        val shown = PlaylistAddress.masked(m3u)
        assertNoLogin(shown, "masked address")
        assertEquals("http://host.example:8080/get.php?username=***&password=***&type=m3u_plus", shown)
    }

    @Test
    fun `masking leaves a login-free address and an empty one alone`() {
        assertEquals("http://panel.example:8080", PlaylistAddress.masked("http://panel.example:8080"))
        assertEquals("", PlaylistAddress.masked(""))
        assertNoLogin(PlaylistAddress.masked("http://alice:s3cret@host.example/list.m3u"), "userinfo address")
        assertNoLogin(PlaylistAddress.masked("http://host.example/live/alice/s3cret/1.ts"), "Xtream path address")
    }

    @Test
    fun `a stored name that is a full address displays as its host`() {
        // Names older builds gave nameless synced rows — and pushed back to the server.
        assertEquals("host.example:8080", PlaylistAddress.displayName(m3u))
        assertNoLogin(PlaylistAddress.displayName("My list $m3u"), "name with an address inside")
        assertEquals("UK Sports", PlaylistAddress.displayName("UK Sports"))
    }

    @Test
    fun `a nameless pulled M3U row is not named after its login-carrying link`() {
        val row = PlaylistRow(sourceType = SOURCE_TYPE_M3U_URL, url = m3u)
        val account = pulledPlaylists(listOf(row)).single().account
        assertNoLogin(account.name, "fallback name")
        assertEquals("host.example:8080", account.name)
        assertEquals("m3u|$m3u", account.id, "the id is unchanged — only the shown name is login-free")
        assertEquals(m3u, account.baseUrl, "the address itself is kept to play from")
    }

    @Test
    fun `a nameless pulled M3U row with a telling file name gets the add form's name`() {
        val row = PlaylistRow(sourceType = SOURCE_TYPE_M3U_URL, url = "http://h.example/lists/uk_sports.m3u?token=s3cret")
        assertEquals("uk sports", pulledPlaylists(listOf(row)).single().account.name)
    }

    @Test
    fun `nameless pulled Xtream and Stalker rows are named without any login`() {
        val xtream = PlaylistRow(sourceType = "xtream", baseUrl = "http://alice:s3cret@panel.example:8080", username = "alice", password = "s3cret")
        val x = pulledPlaylists(listOf(xtream)).single().account
        assertNoLogin(x.name, "Xtream fallback name")
        assertEquals("http://alice:s3cret@panel.example:8080|alice", x.id)

        val stalker = PlaylistRow(
            sourceType = SOURCE_TYPE_STALKER, portalUrl = "http://portal.example/c/?token=s3cret", macAddress = "00:1A:79:00:00:01",
        )
        val s = pulledPlaylists(listOf(stalker)).single().account
        assertNoLogin(s.name, "Stalker fallback name")
        assertEquals("portal.example", s.name)
    }

    @Test
    fun `a pulled row with its own name keeps it`() {
        val row = PlaylistRow(sourceType = SOURCE_TYPE_M3U_URL, url = m3u, name = "Living room")
        assertEquals("Living room", pulledPlaylists(listOf(row)).single().account.name)
        assertTrue(pulledPlaylists(listOf(row)).single().account.baseUrl == m3u)
    }
}
