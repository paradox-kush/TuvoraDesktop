package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B205: a guest who signs in is wiped (AuthRepository.signOut -> LocalAccountDataCleaner.wipe ->
 * DesktopStorage.wipe), and that is exactly when the pending claim is needed. It must not live in anything
 * the wipe clears. The wipe runs on a temp directory, never the real app data.
 */
class PendingDeviceClaimSurvivesWipeTest {
    private val root = Files.createTempDirectory("device-claim-wipe")
    private val claimsFile = root.resolve("${PendingDeviceClaimStorage.STORE_NAME}.properties")
    private val playlistsFile = root.resolve("nuvio_iptv.properties")

    private val claim = PendingDeviceClaim(
        token = "ab".repeat(32), providerName = "Acme TV", createdAtMs = 5L,
        playlists = listOf(DevicePlaylist("k1", "Live", "xtream", baseUrl = "http://p.example.com", username = "u", password = "p")),
    )

    @BeforeTest
    fun setUp() {
        PendingDeviceClaimStorage.storeOverrideForTests = DesktopStorage.Store(claimsFile)
    }

    @AfterTest
    fun tearDown() {
        PendingDeviceClaimStorage.storeOverrideForTests = null
        root.toFile().deleteRecursively()
    }

    @Test
    fun theStoreNameIsRegisteredAsPreservedByTheWipe() {
        assertTrue(PendingDeviceClaimStorage.STORE_NAME in DesktopStorage.PRESERVED_STORE_NAMES)
    }

    @Test
    fun theAccountWipeClearsLocalPlaylistsButLeavesThePendingClaim() {
        val playlists = DesktopStorage.Store(playlistsFile)
        playlists.putString("xtream_accounts_1", "[{\"id\":\"k1\"}]")
        PendingDeviceClaimStore.shared.put(claim)

        DesktopStorage.wipe(root)

        assertNull(DesktopStorage.Store(playlistsFile).getString("xtream_accounts_1"), "the guest's local playlists are wiped, as before")
        assertTrue(Files.exists(claimsFile), "the claim file stays")
        // A fresh store on the same file, as after an app restart.
        PendingDeviceClaimStorage.storeOverrideForTests = DesktopStorage.Store(claimsFile)
        assertEquals(listOf(claim), PendingDeviceClaimStore.shared.list(), "the claim is what brings them back")
    }
}
