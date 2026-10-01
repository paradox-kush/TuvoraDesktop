package com.nuvio.app.features.iptv

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** B23 / UX20 — the NSURLError codes Ktor Darwin surfaces map onto the plain save sentences. */
class PlaylistSaveTransportIosTest {

    private fun darwin(code: Long) =
        DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, code, null))

    @Test
    fun nsUrlErrorsMapToTheSaveVocabulary() {
        assertEquals(PlaylistTransportFailure.SECURE_CONNECTION, classifyPlaylistTransportFailure(darwin(NSURLErrorSecureConnectionFailed)))
        assertEquals(PlaylistTransportFailure.SECURE_CONNECTION, classifyPlaylistTransportFailure(darwin(NSURLErrorServerCertificateUntrusted)))
        assertEquals(PlaylistTransportFailure.UNREACHABLE, classifyPlaylistTransportFailure(darwin(NSURLErrorCannotFindHost)))
        assertEquals(PlaylistTransportFailure.UNREACHABLE, classifyPlaylistTransportFailure(darwin(NSURLErrorTimedOut)))
        assertEquals(PlaylistTransportFailure.INVALID_ADDRESS, classifyPlaylistTransportFailure(darwin(NSURLErrorBadURL)))
        assertNull(classifyPlaylistTransportFailure(IllegalStateException("parse")))
    }
}
