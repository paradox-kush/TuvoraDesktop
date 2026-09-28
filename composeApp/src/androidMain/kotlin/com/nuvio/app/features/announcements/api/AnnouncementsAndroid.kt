package com.nuvio.app.features.announcements.api

import android.content.Context
import com.nuvio.app.features.announcements.internal.AnnouncementStorage

/** Android-only api entry: hands the device-local store its Context (AndroidStartup task). */
object AnnouncementsAndroid {
    fun initialize(context: Context) = AnnouncementStorage.initialize(context)
}
