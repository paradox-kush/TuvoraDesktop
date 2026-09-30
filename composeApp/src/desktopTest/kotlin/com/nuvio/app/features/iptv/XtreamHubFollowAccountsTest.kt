package com.nuvio.app.features.iptv

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Regression (2026-09-30, phone emulator, signed out): after adding an M3U playlist from the IPTV
 * tab's "Add playlist" (which opens Settings), going back to the IPTV tab still said "No IPTV
 * provider yet" until the app was restarted.
 *
 * Root cause: the root tab host keeps a visited tab composed, so the hub screen's one-shot
 * `LaunchedEffect(Unit) { ensureLoaded() }` never ran again and the hub's copy of the playlist
 * list stayed at whatever it was on first entry. [XtreamHubRepository.followAccounts] is what the
 * screen now runs while visible; these drive the store the way the add / delete flows do and
 * assert the hub follows it.
 *
 * Red on the old code: followAccounts only synced once (the old one-shot load), so both time out.
 */
class XtreamHubFollowAccountsTest {

    private fun account(tag: String) = XtreamAccount(
        id = "http://$tag.invalid:8080|u",
        name = tag,
        baseUrl = "http://$tag.invalid:8080",
        username = "u",
        password = "p",
    )

    @AfterTest
    fun tearDown() {
        XtreamRepository.installAccountsForTest(emptyList())
        XtreamHubRepository.resetForProfile()
    }

    @Test
    fun `a playlist added while the hub stays composed shows up without a restart`(): Unit = runBlocking {
        XtreamRepository.installAccountsForTest(emptyList())
        XtreamHubRepository.resetForProfile()
        val follower = launch(Dispatchers.Default) { XtreamHubRepository.followAccounts() }
        try {
            // First entry: the store is empty, so the hub shows "No IPTV provider yet".
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    XtreamHubRepository.uiState.first { it.accountsLoaded && it.accounts.isEmpty() }
                },
                "the hub never loaded its (empty) playlist list",
            )

            // The add flow (verifyAndSave) appends to the store's in-memory list.
            val added = account("added")
            XtreamRepository.stageEditForTest(listOf(added))

            assertNotNull(
                withTimeoutOrNull(5_000) {
                    XtreamHubRepository.uiState.first { st -> st.accounts.any { it.id == added.id } }
                },
                "the added playlist never reached the hub - it stays on the empty state until restart",
            )
        } finally {
            follower.cancel()
        }
    }

    @Test
    fun `a playlist removed while the hub stays composed leaves it and the hub reloads`(): Unit = runBlocking {
        val keep = account("keep")
        val gone = account("gone")
        XtreamRepository.installAccountsForTest(listOf(keep, gone))
        XtreamHubRepository.resetForProfile()
        val follower = launch(Dispatchers.Default) { XtreamHubRepository.followAccounts() }
        try {
            assertNotNull(
                withTimeoutOrNull(5_000) { XtreamHubRepository.uiState.first { it.accounts.size == 2 } },
                "the hub never loaded both playlists",
            )

            // XtreamRepository.remove: the list shrinks, then the hub state is wiped (resetForProfile).
            XtreamRepository.stageEditForTest(listOf(keep))
            XtreamHubRepository.resetForProfile()

            assertNotNull(
                withTimeoutOrNull(5_000) {
                    XtreamHubRepository.uiState.first { st ->
                        st.accountsLoaded && st.accounts.map { it.id } == listOf(keep.id)
                    }
                },
                "after a removal the hub stayed wiped (blank tab) or kept the removed playlist",
            )
        } finally {
            follower.cancel()
        }
    }
}
