package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object IptvContentDbDriver {
    /** Unit tests must not touch the real profile DB — they install an in-memory driver here
     *  (same seam as the Android actual, which host tests use for the identical reason). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: BundledSQLiteDriver().open(desktopDatabasePath("iptv_content.db"))
}
