package com.nuvio.app.core.sync

import android.os.Build

/**
 * "Pixel 8 Pro", "Samsung SM-S911B". Android phones have no user-assigned name an app can read, so
 * the model is the best available. MODEL sometimes already carries the manufacturer (OnePlus does
 * this), hence the prefix check rather than blind concatenation.
 */
internal actual fun syncDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()

    val name = when {
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
    return name.ifBlank { "Android device" }.replaceFirstChar { it.uppercase() }
}
