package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object OverlayDbDriver {
    /** Unit tests install a bundled in-memory driver here (same idiom as MatchDbDriver.desktop). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke() ?: BundledSQLiteDriver().open(desktopDatabasePath("iptv_overlay.db"))
}
