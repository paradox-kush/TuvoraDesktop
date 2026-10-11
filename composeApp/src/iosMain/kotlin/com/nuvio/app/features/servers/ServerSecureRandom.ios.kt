package com.nuvio.app.features.servers

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), bytes.refTo(0))
    check(status == errSecSuccess) { "Secure random generation failed" }
    return bytes
}
