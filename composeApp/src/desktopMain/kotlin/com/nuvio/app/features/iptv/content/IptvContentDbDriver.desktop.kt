package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object IptvContentDbDriver {

    /** Unit tests install a bundled in-memory driver here (mobile's android-actual idiom). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: BundledSQLiteDriver().open(desktopDatabasePath("iptv_content.db"))
}
