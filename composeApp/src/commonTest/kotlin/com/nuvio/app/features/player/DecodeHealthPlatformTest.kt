package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DecodeHealthPlatformTest {
    @Test
    fun `desktop reports the OS family never a version`() {
        assertEquals("macos", decodeHealthPlatformOf(isDesktop = true, isIos = false, platformName = "Desktop Mac OS X"))
        assertEquals("windows", decodeHealthPlatformOf(isDesktop = true, isIos = false, platformName = "Desktop Windows 11"))
        assertEquals("linux", decodeHealthPlatformOf(isDesktop = true, isIos = false, platformName = "Desktop Linux"))
        assertEquals("desktop", decodeHealthPlatformOf(isDesktop = true, isIos = false, platformName = "Desktop FreeBSD"))
    }

    @Test
    fun `shared targets keep their names`() {
        assertEquals("ios", decodeHealthPlatformOf(isDesktop = false, isIos = true, platformName = "iOS 17"))
        assertEquals("android", decodeHealthPlatformOf(isDesktop = false, isIos = false, platformName = "Android 14"))
    }
}
