package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object MatchDbDriver {

    /** Unit tests install a bundled in-memory driver here (same idiom as IptvContentDbDriver.desktop). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: BundledSQLiteDriver().open(desktopDatabasePath("xtream_match.db"))
}
