package com.nuvio.app.features.servers

/** Unguessable per-session secrets (the loopback relay token). */
internal expect fun secureRandomBytes(size: Int): ByteArray

internal fun secureRandomHex(bytes: Int): String =
    secureRandomBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
