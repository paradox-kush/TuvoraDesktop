package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop custom-server store, on a temp file (never the real app-data directory). */
class ServerConfigurationStoreDesktopTest {
    private val dir = Files.createTempDirectory("tuvora-server-config")
    private fun store() = DesktopServerConfigurationStore(DesktopStorage.Store(dir.resolve("server_configuration.properties")))

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val custom = ServerConfiguration(
        backendUrl = "https://sb.example.com",
        publishableKey = "pk",
        capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true, watchParty = true),
        isCustom = true,
        discoveryUrl = "https://example.com/.well-known/nuvio",
    )

    @Test
    fun `a custom server keeps its watch party capability across launches`() {
        assertTrue(store().saveCustom(custom))
        val loaded = store().loadCustom()
        assertEquals(custom.capabilities, loaded?.capabilities, "watch_party from discovery must survive a restart, as on Android and iOS")
        assertEquals(custom.backendUrl, loaded?.backendUrl)
        assertEquals(custom.discoveryUrl, loaded?.discoveryUrl)
    }

    @Test
    fun `switching back to official forgets every custom key`() {
        store().saveCustom(custom)
        store().useOfficial()
        assertNull(store().loadCustom())
        store().saveCustom(custom.copy(capabilities = custom.capabilities.copy(watchParty = false)))
        assertEquals(false, store().loadCustom()?.capabilities?.watchParty)
    }
}
