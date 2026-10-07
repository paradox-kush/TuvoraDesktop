package com.nuvio.app.features.mediaserver.api

import android.content.Context
import com.nuvio.app.features.mediaserver.internal.store.MediaServerStorage
import com.nuvio.app.features.mediaserver.internal.store.PlatformSecureTokenStore

/** Android-only api entry: hands the device-local stores their Context (an AndroidStartup task in AndroidFeatureWiring). */
object MediaServerAndroid {
    fun initialize(context: Context) {
        MediaServerStorage.initialize(context)
        PlatformSecureTokenStore.initialize(context)
    }
}
