package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop media-server sign-in tokens (upstream's `ServerStorage.desktop` kept them in a plain properties file):
 * the sealed file must never hold a readable token.
 */
class SealedFileStoreTest {
    private val dir: Path = Files.createTempDirectory("tuvora-sealed-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private class MemoryProvider(var accepts: Boolean = true) : MasterKeyProvider {
        override val name = "memory"
        var key: ByteArray? = null
        override fun load(): ByteArray? = key
        override fun store(key: ByteArray): Boolean { if (!accepts) return false; this.key = key; return true }
        override fun delete() { key = null }
    }

    private fun store(provider: MasterKeyProvider = MemoryProvider(), ns: String = "ns", file: String = "t.json") =
        SealedFileStore(dir.resolve(file), MasterKeyVault(listOf(provider)), ns)

    @Test
    fun aTokenRoundTripsButNeverTouchesDiskReadable() {
        val s = store()
        s.write("server-1", "SECRET-TOKEN-123")
        assertEquals("SECRET-TOKEN-123", s.read("server-1"))
        val onDisk = Files.readString(dir.resolve("t.json"))
        assertFalse(onDisk.contains("SECRET-TOKEN-123"), "the token must be sealed on disk")
        assertTrue(onDisk.contains("server-1"), "only the item key is visible")
    }

    @Test
    fun aBlobMovedToAnotherKeyOrNamespaceDoesNotOpen() {
        val provider = MemoryProvider()
        val a = store(provider, ns = "servers", file = "a.json")
        a.write("k1", "TOKEN")
        val sealed = Files.readString(dir.resolve("a.json")).replace("\"k1\"", "\"k2\"")
        Files.writeString(dir.resolve("a.json"), sealed)
        assertNull(a.read("k2"), "bound to its key")

        val b = store(provider, ns = "other", file = "a.json")
        assertNull(b.read("k1"), "bound to its namespace")
    }

    @Test
    fun withoutTheMasterKeyTheFileReadsAsEmpty() {
        val provider = MemoryProvider()
        val s = store(provider)
        s.write("k", "TOKEN")
        provider.key = null
        assertNull(store(provider).read("k"))
    }

    @Test
    fun aWriteFailsLoudlyWhenNoProtectedStorageExists() {
        val s = store(MemoryProvider(accepts = false))
        assertFailsWith<java.io.IOException> { s.write("k", "TOKEN") }
        assertFalse(Files.exists(dir.resolve("t.json")))
    }

    @Test
    fun removingTheLastItemDeletesTheFileAndClearAllForgetsTheKey() {
        val provider = MemoryProvider()
        val s = store(provider)
        s.write("k", "TOKEN")
        s.write("k", null)
        assertFalse(Files.exists(dir.resolve("t.json")))
        s.write("k", "TOKEN")
        s.clearAll()
        assertFalse(Files.exists(dir.resolve("t.json")))
        assertNull(provider.key)
    }
}
