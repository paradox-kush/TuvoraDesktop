package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Step 0.3 — the JVM (OkHttp/java.net) exception -> failover-kind mapping, cause chains included. */
class ServerFailoverPlatformKindTest {

    private fun kind(t: Throwable) = classifyFailoverThrowable(t)

    @Test
    fun `jvm network exceptions map to the shared vocabulary`() {
        assertEquals(FailoverFailure(FailoverFailureKind.DNS), kind(UnknownHostException("x")))
        assertEquals(FailoverFailure(FailoverFailureKind.CONNECT_REFUSED), kind(ConnectException("refused")))
        assertEquals(FailoverFailure(FailoverFailureKind.CONNECT_TIMEOUT), kind(SocketTimeoutException("connect timed out")))
        assertEquals(FailoverFailure(FailoverFailureKind.READ_TIMEOUT), kind(SocketTimeoutException("timeout")))
        assertEquals(FailoverFailure(FailoverFailureKind.TLS_HANDSHAKE), kind(SSLHandshakeException("bad cert")))
        assertEquals(FailoverFailure(FailoverFailureKind.HTTP_STATUS, 503), kind(HttpStatusException(503, "x")))
        assertEquals(FailoverFailure(FailoverFailureKind.CANCELLED), kind(CancellationException("x")))
        assertEquals(FailoverFailure(FailoverFailureKind.OTHER), kind(SocketException("Connection reset")))
        assertEquals(FailoverFailure(FailoverFailureKind.OTHER), kind(EOFException()))
        assertEquals(FailoverFailure(FailoverFailureKind.OTHER), kind(IllegalStateException("Authentication failed")))
    }

    @Test
    fun `a wrapped network exception is found through the cause chain`() {
        assertEquals(FailoverFailure(FailoverFailureKind.DNS), kind(IOException("wrapped", UnknownHostException("x"))))
    }
}
