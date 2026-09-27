package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B60 decision 1 (2026-09-27) through the real edit path: a URL edit whose live provider check fails
 * is SAVED (in place, under its new id) and the failure is surfaced as a row warning — the old
 * behaviour refused the save and left the user no way to enter a provider's new domain.
 */
class PlaylistEditSaveAnywayTest {

    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
    }

    @Test
    fun `a URL edit whose provider check fails is saved with a warning`() = runBlocking {
        val old = XtreamAccount(id = "http://old.example:8080|u", name = "P", baseUrl = "http://old.example:8080", username = "u", password = "p")
        XtreamRepository.installAccountsForTest(listOf(old))
        XtreamRepository.persistWriteForTest = { _, _ -> }
        XtreamRepository.verifyForTest = { Result.failure(RuntimeException("Could not reach the panel")) }

        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(
            old.id,
            XtreamFormInput(
                serverUrl = "http://new.example:8080", username = "u", password = "p", name = "P",
                epgUrl = null, dnsProvider = "system", autoRefreshHours = 24,
            ),
        ) { done.complete(it) }

        assertTrue(withTimeout(10_000) { done.await() }, "the edit reports saved")
        val state = XtreamRepository.uiState.value
        assertEquals(listOf("http://new.example:8080"), state.accounts.map { it.baseUrl }, "the new URL is kept, in place")
        val saved = state.accounts.single()
        val warning = assertNotNull(state.saveWarnings[saved.id], "the failed check is surfaced on the row")
        assertTrue(warning.contains("Could not reach the panel"), warning)
    }
}
