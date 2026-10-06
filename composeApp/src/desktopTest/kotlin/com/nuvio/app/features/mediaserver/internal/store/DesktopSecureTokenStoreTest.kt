package com.nuvio.app.features.mediaserver.internal.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSecureTokenStoreTest {
    private val dir: Path = Files.createTempDirectory("tuvora-ms-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A provider that keeps the key in memory and records what happened. */
    private class MemoryProvider(override val name: String = "memory", var accepts: Boolean = true) : MasterKeyProvider {
        var key: ByteArray? = null
        var stores = 0
        var deleted = false
        override fun load(): ByteArray? = key
        override fun store(key: ByteArray): Boolean { if (!accepts) return false; stores++; this.key = key; return true }
        override fun delete() { key = null; deleted = true }
    }

    private fun store(provider: MasterKeyProvider = MemoryProvider()) = DesktopSecureTokenStore(dir.resolve("tokens.json"), MasterKeyVault(listOf(provider)))

    // --- cipher ---

    @Test
    fun theCipherRoundTripsAndEverySealIsFresh() {
        val key = DesktopSecretCipher.newKey()
        val a = DesktopSecretCipher.seal(key, "item", "TOKEN-1")
        val b = DesktopSecretCipher.seal(key, "item", "TOKEN-1")
        assertNotEquals(a, b, "a random IV per seal")
        assertEquals("TOKEN-1", DesktopSecretCipher.open(key, "item", a))
        assertFalse(a.contains("TOKEN-1"))
    }

    @Test
    fun aSealedValueDoesNotOpenForAnotherItemAnotherKeyOrWhenTamperedWith() {
        val key = DesktopSecretCipher.newKey()
        val sealed = DesktopSecretCipher.seal(key, "item-a", "TOKEN-1")
        assertNull(DesktopSecretCipher.open(key, "item-b", sealed), "bound to its item (AAD)")
        assertNull(DesktopSecretCipher.open(DesktopSecretCipher.newKey(), "item-a", sealed), "another key")
        val flipped = sealed.dropLast(3) + (if (sealed.takeLast(3) == "AAA") "BBB" else "AAA")
        assertNull(DesktopSecretCipher.open(key, "item-a", flipped), "tampered")
        assertNull(DesktopSecretCipher.open(key, "item-a", "garbage"))
        assertNull(DesktopSecretCipher.open(key, "item-a", ""))
    }

    // --- store ---

    @Test
    fun valuesRoundTripAndNothingReadableTouchesDisk() {
        val s = store()
        s.write("cred:jellyfin:m:u", """{"accessToken":"TOKEN-SECRET-1"}""")
        s.write("device-id", "abc123")
        assertEquals("""{"accessToken":"TOKEN-SECRET-1"}""", s.read("cred:jellyfin:m:u"))
        assertEquals("abc123", s.read("device-id"))
        val onDisk = Files.readString(dir.resolve("tokens.json"))
        assertFalse(onDisk.contains("TOKEN-SECRET-1") || onDisk.contains("abc123"), "sealed: $onDisk")
        assertNull(s.read("missing"))
        s.write("device-id", null)
        assertNull(s.read("device-id"))
        assertNotNull(s.read("cred:jellyfin:m:u"))
    }

    @Test
    fun theTokenFileIsOwnerOnlyWhereTheFilesystemHasModes() {
        store().write("k", "v")
        val file = dir.resolve("tokens.json")
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun theMasterKeyIsMintedOnceAndOnlyWhenSomethingIsWritten() {
        val provider = MemoryProvider()
        val s = store(provider)
        assertNull(s.read("k"))
        assertEquals(0, provider.stores, "a read never mints a key")
        s.write("k", "v"); s.write("k2", "v2")
        assertEquals(1, provider.stores)
        // a restart (new store, same provider) still reads it
        assertEquals("v", store(provider).read("k"))
    }

    @Test
    fun aKeyThatWentMissingMakesTheTokensReadAsAbsentNotCrash() {
        val provider = MemoryProvider()
        store(provider).write("k", "v")
        provider.key = null // a locked / reset keyring
        assertNull(store(provider).read("k"))
        provider.key = DesktopSecretCipher.newKey() // a different key
        assertNull(store(provider).read("k"))
    }

    @Test
    fun withNoProtectedStorageAtAllAWriteFailsLoudly() {
        val s = store(MemoryProvider(accepts = false))
        assertFailsWith<java.io.IOException> { s.write("k", "v") }
        assertFalse(Files.exists(dir.resolve("tokens.json")))
    }

    @Test
    fun theVaultFallsBackToTheNextProviderWhenTheFirstRefuses() {
        val os = MemoryProvider("os", accepts = false)
        val file = MemoryProvider("file")
        val vault = MasterKeyVault(listOf(os, file))
        val key = vault.key(createIfMissing = true)
        assertNotNull(key)
        assertEquals(0, os.stores); assertEquals(1, file.stores)
        assertTrue(MasterKeyVault(listOf(os, file)).key(false)!!.contentEquals(key))
    }

    @Test
    fun clearAllRemovesTheFileAndTheMasterKey() {
        val provider = MemoryProvider()
        val s = store(provider)
        s.write("k", "v")
        s.clearAll()
        assertFalse(Files.exists(dir.resolve("tokens.json")))
        assertTrue(provider.deleted && provider.key == null)
        assertNull(s.read("k"))
    }

    @Test
    fun aSealedBlobMovedToAnotherItemDoesNotOpen() {
        val provider = MemoryProvider()
        val s = store(provider)
        s.write("cred:a", "TOKEN-A"); s.write("cred:b", "TOKEN-B")
        val file = dir.resolve("tokens.json")
        val text = Files.readString(file)
        // swap the two sealed values in the file
        val values = Regex("\"cred:[ab]\":\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        Files.writeString(file, text.replace(values[0], "@@").replace(values[1], values[0]).replace("@@", values[1]))
        assertNull(store(provider).read("cred:a")); assertNull(store(provider).read("cred:b"))
    }

    // --- providers ---

    private class Recorder(var reply: Pair<Int, String>? = 0 to "") : ProcessRunner {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        override fun run(command: List<String>, stdin: String?, timeoutSeconds: Long): Pair<Int, String>? { calls += command to stdin; return reply }
    }

    private val hexKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theMacOsKeychainProviderUsesTheSecurityTool() {
        val r = Recorder(0 to "$hexKey\n")
        val p = MacOsKeychainProvider(r)
        assertTrue(p.load()!!.contentEquals(keyBytes))
        assertEquals(listOf("/usr/bin/security", "find-generic-password", "-a", "master-key", "-s", "com.nuvio.media.mediaserver", "-w"), r.calls.last().first)
        assertTrue(p.store(keyBytes))
        assertEquals(listOf("/usr/bin/security", "add-generic-password", "-U", "-a", "master-key", "-s", "com.nuvio.media.mediaserver", "-w", hexKey), r.calls.last().first)
        p.delete()
        assertEquals("delete-generic-password", r.calls.last().first[1])
        r.reply = 44 to ""; assertNull(p.load()); assertFalse(p.store(keyBytes))
        r.reply = null; assertNull(p.load()); assertFalse(p.store(keyBytes))
        r.reply = 0 to "not hex"; assertNull(p.load())
    }

    @Test
    fun theLinuxProviderPassesTheSecretOnStdinNeverInArguments() {
        val r = Recorder(0 to hexKey)
        val p = LinuxSecretToolProvider(r)
        assertTrue(p.store(keyBytes))
        assertEquals(hexKey, r.calls.last().second)
        assertFalse(r.calls.last().first.any { it.contains(hexKey) }, "the key is not in argv")
        assertTrue(p.load()!!.contentEquals(keyBytes))
        assertEquals("lookup", r.calls.last().first[1])
        r.reply = 1 to ""; assertNull(p.load())
    }

    @Test
    fun theWindowsProviderProtectsWithDpapiCurrentUserAndNeverStoresThePlainKey() {
        val blob = dir.resolve("master.dpapi")
        val r = Recorder(0 to "PROTECTEDBLOB==\n")
        val p = WindowsDpapiProvider(blob, r)
        assertTrue(p.store(keyBytes))
        assertEquals(hexKey, r.calls.last().second, "the plaintext goes to PowerShell on stdin")
        assertTrue(r.calls.last().first.last().contains("CurrentUser") && r.calls.last().first.last().contains("Protect"))
        assertEquals("PROTECTEDBLOB==", Files.readString(blob).trim())
        assertFalse(Files.readString(blob).contains(hexKey))
        r.reply = 0 to hexKey
        assertTrue(p.load()!!.contentEquals(keyBytes))
        assertEquals("PROTECTEDBLOB==", r.calls.last().second)
        assertTrue(r.calls.last().first.last().contains("Unprotect"))
        p.delete(); assertFalse(Files.exists(blob)); assertNull(p.load())
    }

    @Test
    fun theKeyFileProviderRoundTripsWithOwnerOnlyPermissions() {
        val f = dir.resolve("master.key")
        val p = FileKeyProvider(f)
        assertNull(p.load())
        assertTrue(p.store(keyBytes))
        assertTrue(p.load()!!.contentEquals(keyBytes))
        if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(f))
        }
        p.delete(); assertNull(p.load())
    }

    /** Opt-in: exercises the REAL macOS Keychain (writes then deletes one item). `TUVORA_KEYCHAIN_TEST=1 ./gradlew :composeApp:desktopTest`. */
    @Test
    fun realMacOsKeychainRoundTripWhenOptedIn() {
        if (System.getenv("TUVORA_KEYCHAIN_TEST") != "1" || !System.getProperty("os.name").lowercase().contains("mac")) return
        val p = MacOsKeychainProvider(service = "com.nuvio.media.mediaserver.test", account = "master-key-test")
        try {
            val key = DesktopSecretCipher.newKey()
            assertTrue(p.store(key))
            assertTrue(p.load()!!.contentEquals(key))
        } finally {
            p.delete()
        }
        assertNull(p.load())
    }
}
