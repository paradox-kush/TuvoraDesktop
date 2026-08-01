package com.nuvio.app.core.sync

import platform.UIKit.UIDevice

/**
 * "Kush's iPhone" where the app is entitled to read it; since iOS 16 an unentitled app gets the
 * model name back instead ("iPhone"), which is still a reasonable thing to show.
 */
internal actual fun syncDeviceName(): String {
    val device = UIDevice.currentDevice
    val name = device.name.trim()
    return name.ifBlank { device.model.trim().ifBlank { "iPhone" } }
}
