package com.nuvio.app.features.epg

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object EpgMirrorDbDriver {
    /** Tests inject an in-memory driver so a run never touches the real desktop DB file (the IptvContentDbDriver idiom). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: BundledSQLiteDriver().open(desktopDatabasePath("epg_mirror.db"))
}
