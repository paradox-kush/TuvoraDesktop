package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review H1: the ADD path replaced a managed row by id with a form-built copy (host re-normalised, no backups,
 * no EPG, default user agent), recorded an add and pushed it; the server then detached the playlist silently.
 * Adding the server + login of a playlist a provider manages must leave the pulled row exactly as it is.
 */
class ManagedAddGuardTest {

    private class OkPanel : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?) = """{"user_info":{"auth":1,"status":"Active"}}"""
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) = Unit
    }

    // What the provider installed: awkward on purpose (default port, trailing slash backups, EPG, user agent).
    private val managed = XtreamAccount(
        id = "http://panel.example.com|u1", name = "Acme", baseUrl = "http://panel.example.com",
        username = "u1", password = "p1", epgUrl = "http://epg.example.com/guide.xml", userAgent = "Acme/1.0",
        backupUrls = listOf("http://backup.example.com:80/"),
    )

    @BeforeTest
    fun setUp() {
        XtreamRepository.persistWriteForTest = { _, _ -> }
        ManagedInfoRepository.store = InMemoryManagedInfoStore()
        IptvTransport.current = OkPanel()
    }

    @AfterTest
    fun tearDown() {
        IptvTransport.current = IptvTransport.Platform
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
        ManagedInfoRepository.resetForTest()
    }

    private fun addManual(server: String, name: String?): Boolean = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.addManual(server, "u1", "p1", name) { done.complete(it) }
        withTimeout(10_000) { done.await() }
    }

    @Test
    fun `adding the server and login of a managed playlist leaves the pulled row byte-identical`() {
        XtreamRepository.installAccountsForTest(listOf(managed))
        ManagedInfoRepository.installForTest(1, mapOf(managed.id to ManagedInfo(managed.id, "Acme TV")))
        assertTrue(addManual("http://panel.example.com", name = null))
        val rows = XtreamRepository.uiState.value.accounts
        assertEquals(1, rows.size, "no duplicate")
        assertEquals(managed, rows.single(), "nothing the provider owns was rebuilt from the form")
        val row = playlistPushPayload(rows)[0].jsonObject
        assertEquals(playlistPushPayload(listOf(managed))[0].jsonObject, row)
    }

    @Test
    fun `adding a managed playlist again with a name typed still changes nothing`() {
        XtreamRepository.installAccountsForTest(listOf(managed))
        ManagedInfoRepository.installForTest(1, mapOf(managed.id to ManagedInfo(managed.id, "Acme TV")))
        assertTrue(addManual("http://panel.example.com", name = "Mine"))
        assertEquals(managed, XtreamRepository.uiState.value.accounts.single(), "the name is edited from the playlist, not by re-adding it")
    }

    @Test
    fun `an unmanaged add of an existing key behaves exactly as before - the form-built account replaces it`() {
        XtreamRepository.installAccountsForTest(listOf(managed))   // not in the managed map
        assertTrue(addManual("http://panel.example.com", name = null))
        val row = XtreamRepository.uiState.value.accounts.single()
        assertEquals(emptyList(), row.backupUrls, "an unmanaged re-add is the user's own: built from the form as before")
        assertEquals(null, row.epgUrl)
    }
}
