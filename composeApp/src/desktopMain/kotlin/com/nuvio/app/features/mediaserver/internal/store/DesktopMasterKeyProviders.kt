package com.nuvio.app.features.mediaserver.internal.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Where the desktop's 32-byte master key lives (design 5.3: "desktop OS keychain via the native bridges - file
 * 0600 fallback = named gap"). The session tokens are sealed under this key ([DesktopSecretCipher]) in a 0600 file;
 * the key itself goes to the OS secret store where there is one - macOS Keychain, the Secret Service (libsecret)
 * on Linux, DPAPI on Windows - and ONLY when none is reachable to a 0600 key file beside the data (the named gap:
 * then the protection is file permissions, like an SSH key).
 */
internal interface MasterKeyProvider {
    val name: String

    /** The stored key, or null when absent or this provider is unavailable here. */
    fun load(): ByteArray?

    /** True when the key was stored. */
    fun store(key: ByteArray): Boolean

    fun delete()
}

/** Runs an OS command; the seam that lets the CLI providers be tested without touching a real keychain. */
internal fun interface ProcessRunner {
    /** [stdin] (if any) is written to the process; returns (exit code, stdout) or null when it could not run / timed out. */
    fun run(command: List<String>, stdin: String?, timeoutSeconds: Long): Pair<Int, String>?
}

internal object SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, stdin: String?, timeoutSeconds: Long): Pair<Int, String>? = try {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) } else process.outputStream.close()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.exitValue() to out
        }
    } catch (_: Exception) {
        null
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.fromHex(): ByteArray? = trim().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

/**
 * macOS Keychain through the system `security` tool (a generic password in the login keychain). Known limit,
 * named: `security add-generic-password` takes the secret as an argument, so the MASTER KEY (not a token) is
 * briefly visible in the process list of this user's own machine while it is being stored - once per install.
 */
internal class MacOsKeychainProvider(
    private val runner: ProcessRunner = SystemProcessRunner,
    private val service: String = "com.nuvio.media.mediaserver",
    private val account: String = "master-key",
) : MasterKeyProvider {
    override val name = "macOS Keychain"

    override fun load(): ByteArray? {
        val result = runner.run(listOf("/usr/bin/security", "find-generic-password", "-a", account, "-s", service, "-w"), null, 10) ?: return null
        return if (result.first == 0) result.second.fromHex() else null
    }

    override fun store(key: ByteArray): Boolean =
        runner.run(listOf("/usr/bin/security", "add-generic-password", "-U", "-a", account, "-s", service, "-w", key.toHex()), null, 10)?.first == 0

    override fun delete() {
        runner.run(listOf("/usr/bin/security", "delete-generic-password", "-a", account, "-s", service), null, 10)
    }
}

/** Linux Secret Service (GNOME Keyring / KWallet) through `secret-tool`; the secret travels on stdin, never in argv. */
internal class LinuxSecretToolProvider(
    private val runner: ProcessRunner = SystemProcessRunner,
    private val service: String = "com.nuvio.media.mediaserver",
) : MasterKeyProvider {
    override val name = "Secret Service"

    override fun load(): ByteArray? {
        val result = runner.run(listOf("secret-tool", "lookup", "service", service, "account", "master-key"), null, 10) ?: return null
        return if (result.first == 0) result.second.fromHex() else null
    }

    override fun store(key: ByteArray): Boolean =
        runner.run(listOf("secret-tool", "store", "--label=Tuvora media server key", "service", service, "account", "master-key"), key.toHex(), 10)?.first == 0

    override fun delete() {
        runner.run(listOf("secret-tool", "clear", "service", service, "account", "master-key"), null, 10)
    }
}

/**
 * Windows DPAPI (current-user scope) through PowerShell: the key is stored as a DPAPI-protected blob in a file under
 * the user's profile, so only this Windows user can unwrap it; the plaintext key goes to PowerShell on stdin.
 * UNVERIFIED on a real Windows machine in this lane (the code path is exercised through [ProcessRunner] fakes only).
 */
internal class WindowsDpapiProvider(
    private val blobFile: Path,
    private val runner: ProcessRunner = SystemProcessRunner,
) : MasterKeyProvider {
    override val name = "Windows DPAPI"

    private val prefix = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command")
    private val protect = "Add-Type -AssemblyName System.Security; \$in=[Console]::In.ReadToEnd().Trim(); " +
        "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes(\$in),\$null,'CurrentUser'))"
    private val unprotect = "Add-Type -AssemblyName System.Security; \$in=[Console]::In.ReadToEnd().Trim(); " +
        "[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String(\$in),\$null,'CurrentUser'))"

    override fun load(): ByteArray? {
        val blob = runCatching { Files.readString(blobFile).trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val result = runner.run(prefix + unprotect, blob, 20) ?: return null
        return if (result.first == 0) result.second.fromHex() else null
    }

    override fun store(key: ByteArray): Boolean {
        val result = runner.run(prefix + protect, key.toHex(), 20) ?: return false
        if (result.first != 0 || result.second.isBlank()) return false
        return runCatching { writePrivate(blobFile, result.second.trim()) }.isSuccess
    }

    override fun delete() {
        runCatching { Files.deleteIfExists(blobFile) }
    }
}

/** The named-gap fallback: the key in a 0600 file next to the data. Only used when no OS secret store answered. */
internal class FileKeyProvider(private val keyFile: Path) : MasterKeyProvider {
    override val name = "key file"

    override fun load(): ByteArray? = runCatching { Files.readString(keyFile).fromHex() }.getOrNull()

    override fun store(key: ByteArray): Boolean = runCatching { writePrivate(keyFile, key.toHex()) }.isSuccess

    override fun delete() {
        runCatching { Files.deleteIfExists(keyFile) }
    }
}

/** Writes [text] to [path] atomically with owner-only permissions where the filesystem supports POSIX modes. */
internal fun writePrivate(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
    try {
        Files.deleteIfExists(tmp)
        runCatching {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.onFailure { Files.createFile(tmp) }
        Files.writeString(tmp, text)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        runCatching { Files.deleteIfExists(tmp) }
    }
}

/**
 * The master key's custody: tries the OS secret store for this platform first, then the 0600 key file. A key is
 * minted once and stored in the FIRST provider that accepts it; later runs read it back from there. If a provider that
 * used to hold it stops answering (a locked keyring), reads return null -> the tokens read as absent and the user signs in again.
 */
internal class MasterKeyVault(private val providers: List<MasterKeyProvider>) {
    @Volatile private var cached: ByteArray? = null

    /** The key, minting and storing one when none exists anywhere yet. Null only when no provider can store one. */
    @Synchronized
    fun key(createIfMissing: Boolean): ByteArray? {
        cached?.let { return it }
        for (p in providers) p.load()?.let { cached = it; return it }
        if (!createIfMissing) return null
        val fresh = DesktopSecretCipher.newKey()
        for (p in providers) if (p.store(fresh)) { cached = fresh; return fresh }
        return null
    }

    @Synchronized
    fun forget() {
        cached = null
        providers.forEach(MasterKeyProvider::delete)
    }

    companion object {
        fun forThisMachine(dataDir: Path): MasterKeyVault {
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val providers = buildList<MasterKeyProvider> {
                when {
                    os.contains("mac") -> add(MacOsKeychainProvider())
                    os.contains("win") -> add(WindowsDpapiProvider(dataDir.resolve("mediaserver-master-key.dpapi")))
                    else -> add(LinuxSecretToolProvider())
                }
                add(FileKeyProvider(dataDir.resolve("mediaserver-master.key")))
            }
            return MasterKeyVault(providers)
        }
    }
}
