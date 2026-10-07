package com.nuvio.app.features.mediaserver.internal.client

import com.nuvio.app.features.mediaserver.internal.policy.CertTrustPolicy
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount

/**
 * One Darwin client per server authority. NSURLSession validates the chain against the system first
 * (`SecTrustEvaluateWithError`); ONLY when that fails does the shared [CertTrustPolicy] decide (via
 * [MediaServerTrust.check]) whether a certificate this device pinned applies - the delegate then answers the
 * challenge with a credential for exactly that trust, or cancels it. A publicly trusted chain is never pinned.
 *
 * Named gap: Security.framework does not say WHY an evaluation failed, so a failure is classified self-signed
 * (single-certificate chain) or untrusted-chain; the "hostname mismatch only on a LAN/IP host" refinement of
 * the policy is exercised on Android/desktop only. A user-consented pin is still required here either way.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun createMediaServerHttpClient(authority: String, trust: MediaServerTrust): HttpClient =
    HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        engine {
            handleChallenge { _, _, challenge, completionHandler ->
                val space = challenge.protectionSpace
                val serverTrust = space.serverTrust
                if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust || serverTrust == null) {
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                    return@handleChallenge
                }
                val systemTrusted = SecTrustEvaluateWithError(serverTrust, null)
                if (systemTrusted) {
                    completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(serverTrust))
                    return@handleChallenge
                }
                val leaf = SecTrustGetCertificateAtIndex(serverTrust, 0)
                val fingerprint = leaf?.let(::sha256Hex).orEmpty()
                val kind = if (SecTrustGetCertificateCount(serverTrust) <= 1L) CertTrustPolicy.FailureKind.SELF_SIGNED else CertTrustPolicy.FailureKind.UNTRUSTED_CHAIN
                if (fingerprint.isNotEmpty() && trust.check(authority, false, kind, fingerprint)) {
                    completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(serverTrust))
                } else {
                    completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                }
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun sha256Hex(certificate: platform.Security.SecCertificateRef): String {
    val data = SecCertificateCopyData(certificate) ?: return ""
    try {
        val length = CFDataGetLength(data).toInt()
        val bytes = CFDataGetBytePtr(data) ?: return ""
        val input = UByteArray(length) { bytes[it] }
        val output = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        input.usePinned { inPinned ->
            output.usePinned { outPinned ->
                CC_SHA256(inPinned.addressOf(0), length.toUInt(), outPinned.addressOf(0))
            }
        }
        return output.joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    } finally {
        CFRelease(data)
    }
}
