package com.nuvio.app.features.servers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Starting Quick Connect against a self-signed server the device has not trusted yet must surface the same
 * trust-on-first-use decision as a password sign-in. Found on a real Jellyfin behind a self-signed front: the sheet showed
 * "Quick Connect did not finish" and never offered the certificate, because nothing read the driver's certificate.
 */
class QuickConnectDriverCertificateTest {
    private val untrusted = ServerCertificateException("10.0.2.2:18444", "self-signed", "AB:CD", changed = false)

    private class Provider(private val answers: MutableList<() -> QuickConnectChallenge>) : ServerProvider by FakeServerProvider() {
        override val capabilities = setOf(ServerCapability.QUICK_CONNECT)
        override suspend fun quickConnectStart(address: String): QuickConnectChallenge = answers.removeAt(0)()
    }

    private fun challenge() = QuickConnectChallenge("123456", "secret", "https://10.0.2.2:18444", "srv", "Server")

    @Test
    fun `an untrusted certificate while starting is offered for the trust decision`() = runTest {
        val driver = QuickConnectDriver(Provider(mutableListOf({ throw untrusted })), "https://10.0.2.2:18444")
        assertNull(driver.certificateToPrompt(), "nothing to decide before anything ran")
        driver.start()
        assertEquals(QuickConnectState.Failed(QuickConnectFailure.CERTIFICATE), driver.state.value)
        assertSame(untrusted, driver.certificateToPrompt())
    }

    @Test
    fun `the decision goes away once the request is retried or cancelled`() = runTest {
        val driver = QuickConnectDriver(
            Provider(mutableListOf({ throw untrusted }, { challenge() })),
            "https://10.0.2.2:18444",
        )
        driver.start()
        assertSame(untrusted, driver.certificateToPrompt())
        driver.start() // after trusting, the sheet restarts the request
        assertNull(driver.certificateToPrompt(), "a code is on screen: nothing left to trust")
        driver.cancel()
        assertNull(driver.certificateToPrompt())
    }

    @Test
    fun `other failures are not a certificate decision`() = runTest {
        val driver = QuickConnectDriver(Provider(mutableListOf({ throw ServerException(ServerFailure.UNREACHABLE) })), "https://x")
        driver.start()
        assertNull(driver.certificateToPrompt())
    }
}
