package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object MatchDbDriver {
    actual fun openConnection(): SQLiteConnection =
        BundledSQLiteDriver().open(desktopDatabasePath("xtream_match.db"))
}
