package com.nuvio.app.core.telemetry.internal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstallIdentityTest {
    private fun identityIn(dir: java.nio.file.Path) = DesktopInstallIdentity(DesktopTelemetryFileStore(dir))

    @Test
    fun theSameStorageDirectoryYieldsTheSameIdAcrossLaunches() {
        val dir = Files.createTempDirectory("tuvora-telemetry-a")
        val first = identityIn(dir).loadOrCreate()
        val second = identityIn(dir).loadOrCreate() // a "second launch": brand-new object, same directory
        assertEquals(first, second, "the install id must persist across launches")
        assertEquals(first, identityIn(dir).peek())
    }

    @Test
    fun aFreshDirectoryGetsADifferentRandomId() {
        val a = identityIn(Files.createTempDirectory("tuvora-telemetry-b")).loadOrCreate()
        val b = identityIn(Files.createTempDirectory("tuvora-telemetry-c")).loadOrCreate()
        assertNotEquals(a, b)
        assertEquals(36, a.length, "a random UUID, not derived from hardware, account or user name")
    }

    @Test
    fun peekNeverCreatesAFile() {
        val dir = Files.createTempDirectory("tuvora-telemetry-d")
        assertNull(identityIn(dir).peek(), "nothing is stored until diagnostics are on")
        assertTrue(Files.list(dir).use { it.count() } == 0L)
    }

    @Test
    fun aCorruptFileIsReplacedNotTrusted() {
        val dir = Files.createTempDirectory("tuvora-telemetry-e")
        Files.write(dir.resolve("install_id.txt"), "not-an-id".toByteArray())
        val id = identityIn(dir).loadOrCreate()
        assertNotNull(id)
        assertNotEquals("not-an-id", id)
    }
}
