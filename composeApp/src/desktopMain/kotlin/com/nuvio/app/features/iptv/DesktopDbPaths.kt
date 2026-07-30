package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files

/** Absolute path for a named SQLite db under the desktop app-data dir (created on demand). */
internal fun desktopDatabasePath(name: String): String {
    val dir = DesktopStorage.rootDir.resolve("databases")
    Files.createDirectories(dir)
    return dir.resolve(name).toAbsolutePath().toString()
}
