package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B57 (GitHub #19): deleting a playlist must be discoverable and deliberate. Remove used to sit in the
 * dialog's Cancel slot, styled like "Disable", and deleted at once with no confirmation.
 */
class PlaylistActionsPolicyTest {

    private fun account(sourceType: String) = XtreamAccount(
        id = "$sourceType|id", name = "My Provider", baseUrl = "http://h", username = "u", password = "p",
        sourceType = sourceType,
    )

    private val allSourceTypes = listOf(SOURCE_TYPE_XTREAM, SOURCE_TYPE_M3U_URL, SOURCE_TYPE_M3U_FILE, SOURCE_TYPE_STALKER)

    @Test
    fun `every playlist type offers a labelled Remove playlist action in the dialog body`() {
        allSourceTypes.forEach { type ->
            val actions = PlaylistActionsPolicy.bodyActions(account(type))
            assertTrue(PlaylistAction.REMOVE in actions, "$type: Remove is a listed action and not the Cancel slot")
            assertEquals(PlaylistAction.REMOVE, actions.last(), "$type: the destructive action comes last")
        }
        assertEquals("Remove playlist", PlaylistActionsPolicy.label(PlaylistAction.REMOVE), "label says what is removed")
    }

    @Test
    fun `Remove is destructive and must be confirmed`() {
        assertTrue(PlaylistActionsPolicy.isDestructive(PlaylistAction.REMOVE), "styled as destructive")
        assertTrue(PlaylistActionsPolicy.requiresConfirmation(PlaylistAction.REMOVE), "never deletes on one tap")
        PlaylistAction.entries.filter { it != PlaylistAction.REMOVE }.forEach {
            assertFalse(PlaylistActionsPolicy.isDestructive(it), "$it is not destructive")
            assertFalse(PlaylistActionsPolicy.requiresConfirmation(it), "$it needs no confirmation")
        }
    }

    @Test
    fun `the confirmation names the playlist and what goes with it`() {
        val acc = account(SOURCE_TYPE_XTREAM)
        assertEquals("Remove playlist?", PlaylistActionsPolicy.removeConfirmTitle(acc), "title")
        val message = PlaylistActionsPolicy.removeConfirmMessage(acc)
        assertTrue("My Provider" in message, "names the playlist: $message")
        assertTrue("favourites" in message && "watch progress" in message, "says what is lost: $message")
    }

    @Test
    fun `re-match is offered only for Xtream playlists`() {
        allSourceTypes.forEach { type ->
            assertEquals(
                type == SOURCE_TYPE_XTREAM,
                PlaylistAction.REMATCH in PlaylistActionsPolicy.bodyActions(account(type)),
                "$type re-match",
            )
        }
    }
}
