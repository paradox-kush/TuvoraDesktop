package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
