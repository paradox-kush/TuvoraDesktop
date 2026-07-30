package com.nuvio.app.features.epg

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object EpgMirrorDbDriver {
    actual fun openConnection(): SQLiteConnection =
        BundledSQLiteDriver().open(desktopDatabasePath("epg_mirror.db"))
}
