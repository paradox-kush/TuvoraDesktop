package com.nuvio.app.features.servers

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
