package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDevicePlatformTest {
    // The Desktop app inherited the mobile client-id prefix, so this reported value is the only
    // thing that lets account_devices list it as a desktop (report_device, 20260927020000).
    @Test
    fun desktop_reports_the_desktop_platform() {
        assertEquals("desktop", syncDevicePlatform(), "p_platform sent to report_device")
    }
}
