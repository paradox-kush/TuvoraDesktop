package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.nuvio.app.features.iptv.desktopDatabasePath

internal actual object IptvContentDbDriver {
    actual fun openConnection(): SQLiteConnection =
        BundledSQLiteDriver().open(desktopDatabasePath("iptv_content.db"))
}
