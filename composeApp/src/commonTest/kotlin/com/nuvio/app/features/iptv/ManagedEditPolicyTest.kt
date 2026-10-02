package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The guard against SILENT DETACH (contract section 6). The server detaches a managed playlist when a
 * pushed row changes any provider-owned field; the edit paths used to rebuild the account from form
 * fields and re-normalise the address, so a plain RENAME changed a byte. These run through the real
 * edit path ([XtreamRepository.editFromForm]) and compare the row that would be PUSHED.
 */
class ManagedEditPolicyTest {

    /** The wire fields the server's detach trigger compares (iptv_managed_fields_differ). */
    private val providerOwnedWireKeys = listOf(
        "source_type", "base_url", "url", "portal_url", "username", "password", "mac_address",
        "stalker_username", "stalker_password", "backup_urls", "epg_url", "user_agent",
    )

    private val xtream = XtreamAccount(
        id = "http://panel.example.com|u1", name = "Acme", baseUrl = "http://Panel.Example.COM:80/",
        username = "u1", password = "p1", epgUrl = "http://EPG.Example.com:80/guide.xml/",
        userAgent = "Acme/1.0", backupUrls = listOf("HTTP://Backup.Example.com:80/", "http://backup2.example.com:8080/"),
    )
    private val m3u = XtreamAccount(
        id = "m3u|http://cdn.example.com/list.m3u", name = "Acme M3U", baseUrl = "HTTP://Cdn.Example.com:80/list.m3u?token=AbC ",
        username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL, userAgent = "Acme/2.0",
        backupUrls = listOf("http://Mirror.Example.com:80/list.m3u/"),
    )
    private val stalker = XtreamAccount(
        id = "stalker|http://portal.example.com|00:1A:79:AB:CD:EF", name = "Acme Stalker", baseUrl = "http://Portal.Example.com:80/c/",
        username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1a:79:ab:cd:ef",
        stalkerUsername = "stu", stalkerPassword = " stp ", backupUrls = listOf("http://Portal2.Example.com:80/c/"),
    )

    @BeforeTest
    fun setUp() {
        XtreamRepository.persistWriteForTest = { _, _ -> }
        ManagedInfoRepository.store = InMemoryManagedInfoStore()
    }

    @AfterTest
    fun reset() {
        XtreamRepository.verifyForTest = null
        XtreamRepository.persistWriteForTest = null
        XtreamRepository.clearLocalState()
        ManagedInfoRepository.resetForTest()
    }

    private fun managed(vararg accounts: XtreamAccount) {
        XtreamRepository.installAccountsForTest(accounts.toList())
        ManagedInfoRepository.installForTest(
            1, accounts.associate { it.id to ManagedInfo(playlistKey = it.id, providerName = "Acme TV") },
        )
    }

    /** The form exactly as the locked-or-not Edit screen would hand it over: every field as shown. */
    private fun formOf(account: XtreamAccount, name: String) = XtreamFormInput(
        serverUrl = if (account.sourceType == SOURCE_TYPE_M3U_URL) "" else account.baseUrl,
        username = account.username, password = account.password, name = name,
        epgUrl = account.epgUrl, dnsProvider = account.dnsProvider, autoRefreshHours = account.autoRefreshHours,
        sourceType = account.sourceType,
        m3uUrl = if (account.sourceType == SOURCE_TYPE_M3U_URL) account.baseUrl else "",
        userAgent = account.userAgent, macAddress = account.macAddress,
        stalkerUsername = account.stalkerUsername, stalkerPassword = account.stalkerPassword,
        backupUrls = account.backupUrls,
    )

    private fun rename(account: XtreamAccount, name: String): XtreamAccount = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.editFromForm(account.id, formOf(account, name)) { done.complete(it) }
        assertTrue(withTimeout(10_000) { done.await() }, "the edit reports saved")
        XtreamRepository.uiState.value.accounts.single { it.id == account.id }
    }

    private fun pushRow(account: XtreamAccount): JsonObject = playlistPushPayload(listOf(account))[0].jsonObject

    private fun assertProviderFieldsIdentical(pulled: XtreamAccount, after: XtreamAccount) {
        val before = pushRow(pulled)
        val now = pushRow(after)
        for (key in providerOwnedWireKeys) {
            assertEquals(before[key], now[key], "pushed `$key` of a renamed MANAGED playlist must equal the pulled value")
        }
        assertEquals(pulled, after.copy(name = pulled.name), "nothing but the name may change")
    }

    @Test
    fun `renaming a managed Xtream playlist keeps every provider-owned field byte-identical`() {
        managed(xtream)
        val after = rename(xtream, "My Acme")
        assertEquals("My Acme", after.name)
        assertProviderFieldsIdentical(xtream, after)
    }

    @Test
    fun `renaming a managed M3U-URL playlist keeps every provider-owned field byte-identical`() {
        managed(m3u)
        val after = rename(m3u, "Renamed M3U")
        assertEquals("Renamed M3U", after.name)
        assertProviderFieldsIdentical(m3u, after)
    }

    @Test
    fun `renaming a managed Stalker playlist keeps every provider-owned field byte-identical`() {
        managed(stalker)
        val after = rename(stalker, "Renamed Stalker")
        assertEquals("Renamed Stalker", after.name)
        assertProviderFieldsIdentical(stalker, after)
    }

    @Test
    fun `a managed playlist's backup URL with a trailing slash and uppercase host and default port survives a rename`() {
        managed(xtream)
        val after = rename(xtream, "Renamed")
        assertEquals(xtream.backupUrls, after.backupUrls, "backup URLs are not re-normalised")
        assertEquals("http://Panel.Example.COM:80/", after.baseUrl, "the address is not re-normalised")
        assertFalse(ManagedEditPolicy.changesProviderFields(xtream, after), "the server would not detach it")
    }

    /** The ADD path, as Add Playlist drives it (code review H1, security M3). */
    private fun addFromForm(input: XtreamFormInput): Boolean = runBlocking {
        val done = CompletableDeferred<Boolean>()
        XtreamRepository.addFromForm(input) { done.complete(it) }
        withTimeout(10_000) { done.await() }
    }

    @Test
    fun `adding the same server and login as a managed playlist never rewrites its provider fields`() {
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        // The customer types the very server + login the provider installed, differently written.
        val typed = formOf(xtream, "Typed by hand").copy(
            serverUrl = "HTTP://PANEL.example.com", epgUrl = null, userAgent = null, backupUrls = emptyList(),
        )
        val key = xtreamAccountFromForm(typed)!!.id
        val pulled = xtream.copy(id = key)
        managed(pulled)
        assertTrue(addFromForm(typed), "the add reports saved")
        val rows = XtreamRepository.uiState.value.accounts
        assertEquals(1, rows.size, "no duplicate row")
        val after = rows.single()
        for (wireKey in providerOwnedWireKeys) {
            assertEquals(pushRow(pulled)[wireKey], pushRow(after)[wireKey], "pushed `$wireKey` of an ADDED-over managed playlist must equal the pulled value")
        }
        assertFalse(ManagedEditPolicy.changesProviderFields(pulled, after), "the server would not detach it")
    }

    @Test
    fun `adding a playlist with the same login as an UNmanaged one still replaces it as before`() {
        XtreamRepository.verifyForTest = { Result.success(Unit) }
        val typed = formOf(xtream, "Mine").copy(serverUrl = "HTTP://PANEL.example.com", userAgent = "Mine/9")
        val key = xtreamAccountFromForm(typed)!!.id
        XtreamRepository.installAccountsForTest(listOf(xtream.copy(id = key)))
        assertTrue(addFromForm(typed))
        assertEquals("Mine/9", XtreamRepository.uiState.value.accounts.single().userAgent, "an unmanaged playlist is the user's to overwrite")
    }

    @Test
    fun `a blank new name keeps the managed playlist's name`() {
        managed(xtream)
        assertEquals("Acme", rename(xtream, "   ").name)
    }

    @Test
    fun `an UNmanaged rename behaves exactly as today and still normalises the address`() {
        XtreamRepository.installAccountsForTest(listOf(xtream))   // not in the managed map
        val after = rename(xtream, "Mine")
        assertEquals("Mine", after.name)
        assertNotEquals(xtream.baseUrl, after.baseUrl, "an unmanaged edit goes through the form's normalisation, as before")
        assertEquals(xtreamAccountFromForm(formOf(xtream, "Mine"))?.baseUrl, after.baseUrl)
        assertEquals(xtreamAccountFromForm(formOf(xtream, "Mine"))?.backupUrls, after.backupUrls)
    }

    @Test
    fun `any candidate is locked back to the pulled provider fields`() {
        val tampered = xtream.copy(
            baseUrl = "http://other.example.com", username = "x", password = "y", epgUrl = null, userAgent = null,
            backupUrls = emptyList(), macAddress = "AA", stalkerUsername = "s", stalkerPassword = "t", sourceType = SOURCE_TYPE_STALKER,
            name = "New", enabled = false,
        )
        val locked = ManagedEditPolicy.lockProviderFields(xtream, tampered)
        assertProviderFieldsIdentical(xtream, locked.copy(name = xtream.name, enabled = xtream.enabled))
        assertEquals("New", locked.name, "the name is the user's")
        assertFalse(locked.enabled, "the on/off switch is the user's")
    }

    @Test
    fun `changing a provider-owned field is detected`() {
        assertTrue(ManagedEditPolicy.changesProviderFields(xtream, xtream.copy(baseUrl = "http://panel.example.com")))
        assertTrue(ManagedEditPolicy.changesProviderFields(xtream, xtream.copy(backupUrls = emptyList())))
        assertTrue(ManagedEditPolicy.changesProviderFields(xtream, xtream.copy(password = "p2")))
        assertFalse(ManagedEditPolicy.changesProviderFields(xtream, xtream.copy(name = "x", enabled = false, dnsProvider = "google")))
    }

    @Test
    fun `source type chips are not selectable in edit mode for any playlist`() {
        assertFalse(ManagedPlaylistPolicy.sourceTypeSelectable(editing = true))
        assertTrue(ManagedPlaylistPolicy.sourceTypeSelectable(editing = false))
    }

    @Test
    fun `managed playlists lock server and login and unmanaged ones do not`() {
        assertFalse(ManagedPlaylistPolicy.showEditServerLogin(managed = true))
        assertTrue(ManagedPlaylistPolicy.showEditServerLogin(managed = false))
        assertEquals(setOf(PlaylistField.NAME), ManagedPlaylistPolicy.editScreenFields(managed = true))
        assertFalse(PlaylistField.SERVER_LOGIN in ManagedPlaylistPolicy.editableFields(managed = true))
        assertFalse(PlaylistField.BACKUP_SERVERS in ManagedPlaylistPolicy.editableFields(managed = true))
        assertTrue(PlaylistField.NAME in ManagedPlaylistPolicy.editableFields(managed = true))
        assertEquals(PlaylistField.entries.toSet(), ManagedPlaylistPolicy.editableFields(managed = false))
    }

    @Test
    fun `isManaged is a key lookup and the owner label names the provider`() {
        val info = ManagedInfo(playlistKey = "k", providerName = "Acme TV")
        assertTrue(ManagedPlaylistPolicy.isManaged("k", mapOf("k" to info)))
        assertFalse(ManagedPlaylistPolicy.isManaged("other", mapOf("k" to info)))
        assertEquals("Managed by Acme TV", ManagedPlaylistPolicy.ownerLabel(info))
    }
}
