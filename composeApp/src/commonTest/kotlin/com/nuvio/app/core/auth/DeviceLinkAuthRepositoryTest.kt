package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

// Regression for 2026-09-28: "sign in with another device" pointed people at upstream's nuvio.tv/link,
// and called an RPC this backend lacked. The backend now serves start_device_login_session and the
// approval page lives on tuvora.co (nuvio-backend 20260928120000_device_login.sql, nuvio-web /link).
class DeviceLinkAuthRepositoryTest {

    @Test
    fun `official link sends people to the Tuvora approval page`() {
        assertEquals("https://tuvora.co/link", DeviceLinkAuthRepository.officialLinkUrl)
    }

    @Test
    fun `the six character code from the backend is shown split in two`() {
        assertEquals("ABC-D2F", formatDeviceLinkCode("ABCD2F"))
        assertEquals("ABC-D2F", formatDeviceLinkCode("abc-d2f"))
    }

    // Regression (2026-10-04): desktop device sign-in reported "mobile", so tuvora.co listed the
    // Mac/PC session as a phone. It must use the same platform token the desktop sync payloads use.
    @Test
    fun `desktop sign-in reports the desktop device type`() {
        val params = deviceLinkStartParams("nonce", "https://tuvora.co/link", "MacBook")
        assertEquals("desktop", params.getValue("p_device_type").jsonPrimitive.content)
    }
}
