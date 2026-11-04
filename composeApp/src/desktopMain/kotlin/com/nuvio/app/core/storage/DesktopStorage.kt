package com.nuvio.app.core.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    /**
     * Stores the account wipe must NOT clear. B205: the pending device-setup claim is what carries playlists
     * redeemed with no account into the account, and the sign-in of a guest is exactly when this wipe runs.
     */
    internal val PRESERVED_STORE_NAMES: Set<String> = setOf("tuvora_device_setup_claims")

    private val preservedFileNames: Set<String> = PRESERVED_STORE_NAMES.map { "$it.properties" }.toSet()

    fun wipe() = wipe(rootDir)

    /** [root] is a parameter so a test wipes a temp directory, never the real app data. */
    internal fun wipe(root: Path) {
        // The in-memory stores belong to the real data directory; a wipe of any other directory (a test's) leaves them be.
        if (root == rootDir) {
            synchronized(stores) {
                val doomed = stores.keys.filter { it !in PRESERVED_STORE_NAMES }
                doomed.forEach { name -> stores.remove(name)?.clearInMemory() }
            }
        }
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != root }
                .filter { path -> !(path.parent == root && path.fileName.toString() in preservedFileNames) }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Application Support/Tuvora")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let(Paths::get) ?: userHome.resolve("AppData/Roaming")).resolve("Tuvora")
            }
            else -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                (xdgConfig?.let(Paths::get) ?: userHome.resolve(".config")).resolve("tuvora")
            }
        }
    }

    private fun resolveCacheDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        return when {
            osName.contains("mac") -> userHome.resolve("Library/Caches/Nuvio")
            osName.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                (localAppData?.let(Paths::get) ?: userHome.resolve("AppData/Local")).resolve("Nuvio/Cache")
            }
            else -> {
                val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                (xdgCache?.let(Paths::get) ?: userHome.resolve(".cache")).resolve("nuvio")
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        /** Every key currently stored (the legacy media-server import lists its per-profile entries with it). */
        fun keys(): Set<String> = synchronized(lock) {
            ensureLoaded()
            properties.stringPropertyNames().toSet()
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            if (properties.remove(key) != null) persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            var changed = false
            keys.forEach { key ->
                if (properties.remove(key) != null) changed = true
            }
            if (changed) persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            runCatching {
                Files.newInputStream(file).use { input ->
                    properties.load(input)
                }
            }
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { output ->
                properties.store(output, "Tuvora desktop preferences")
            }
        }
    }
}
