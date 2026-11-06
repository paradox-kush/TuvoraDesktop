package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPhysicalMemoryTest {
    @Test
    fun `the jvm reports the installed memory as a positive size`() {
        val bytes = DesktopPhysicalMemory.bytes()
        assertTrue(bytes >= 256L * 1024 * 1024, "physical memory read as $bytes bytes")
    }
}
