package com.nuvio.app.features.servers

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real upgrade: a throwaway user.home that the RELEASED v0.9.5 stack populated (sealed token file, key file, properties with the
 * entry and the pin) against the Jellyfin rig, opened by this build with no sign-in. Skipped unless the proof's env is present:
 *   TUVORA_UPGRADE_HOME=<throwaway home seeded by v0.9.5>  TUVORA_MS_E2E_JELLYFIN_TLS=url|user|pass  TUVORA_MS_E2E_HARNESS=<e2e-media dir>
 * Run it alone (`--tests '*LegacyUpgradeLiveE2ETest'`): it re-points user.home and turns the real file storage on (key file only,
 * never the OS keychain), which must happen before anything else in the JVM touches the app-data directory.
 */
class LegacyUpgradeLiveE2ETest {
    @Test
    fun `servers token and pin saved by the released stack carry over - browse works with no sign-in`() = runBlocking<Unit> {
        val home = System.getenv("TUVORA_UPGRADE_HOME")
        val spec = System.getenv("TUVORA_MS_E2E_JELLYFIN_TLS")
        val harness = System.getenv("TUVORA_MS_E2E_HARNESS")?.let(::File)
        org.junit.Assume.assumeTrue("upgrade proof env missing", home != null && spec != null && harness?.isDirectory == true)
        System.setProperty("user.home", home!!)
        System.setProperty(ServerStorage.IN_MEMORY_PROPERTY, "false")
        System.setProperty("tuvora.vault.fileKeyOnly", "true")
        val url = spec!!.substringBefore('|')
        val authority = serverAuthority(url)!!

        ServerRepository.ensureLoaded() // the first load runs the legacy import before anything reads the store
        val connection = assertNotNull(ServerRepository.uiState.value.connections.singleOrNull(), "the saved server was imported")
        assertEquals("jellyfin", connection.providerId)
        assertEquals(url, connection.address)
        assertTrue(connection.importWatchState, "continue_watching home row -> import watch state")

        val lines = File(harness!!, "fingerprints.txt").readLines()
        val start = lines.indexOfFirst { it.startsWith("front A ORIGINAL") }
        val expectedPin = lines.drop(start + 1).first { it.trim().startsWith("cert sha256:") }.substringAfter(':').trim().replace(":", "")
        assertEquals(expectedPin, ServerTrust.shared.pinnedFingerprint(authority), "the pin the released stack stored is honoured")

        val session = assertNotNull(ServerRepository.session(connection.id), "the token was carried: signed in with no password")
        val provider = assertNotNull(ServerProviders.forId(connection.providerId))
        ServerRepository.refreshLibraries(connection.id)
        val libraries = ServerRepository.uiState.value.connections.single().libraries
        val movies = assertNotNull(libraries.firstOrNull { it.kind == ServerMediaKind.MOVIE }, "movie library listed: $libraries")
        val page = provider.libraryPage(session, movies, 0, 20)
        assertTrue(page.items.any { it.preview.name.contains("Test Movie") }, "browse works: ${page.items.map { it.preview.name }}")
    }
}
