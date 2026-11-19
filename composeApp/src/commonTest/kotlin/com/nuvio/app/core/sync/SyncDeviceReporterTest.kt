package com.nuvio.app.core.sync

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDeviceReporterTest {
    @Test
    fun reportDeviceArgumentsCarryTheAppVersion() {
        val args = reportDeviceArguments(
            clientId = "client-1",
            deviceName = "Kitchen laptop",
            platform = "desktop",
            appVersion = "0.17.0",
        )
        assertEquals("0.17.0", args["p_app_version"]?.jsonPrimitive?.content, "p_app_version must be sent")
        assertEquals("client-1", args["p_client_id"]?.jsonPrimitive?.content)
        assertEquals("Kitchen laptop", args["p_device_name"]?.jsonPrimitive?.content)
        assertEquals("desktop", args["p_platform"]?.jsonPrimitive?.content)
    }
}
