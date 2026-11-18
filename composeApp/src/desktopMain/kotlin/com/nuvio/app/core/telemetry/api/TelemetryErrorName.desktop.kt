package com.nuvio.app.core.telemetry.api

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * JDK types by `is` check (most specific first); the strings are fixed here, never read from the class. The desktop
 * build is not minified today, but the reported name must not depend on that.
 */
internal actual fun platformErrorName(error: Throwable): String? = when (error) {
    is UnknownHostException -> "UnknownHostException"
    is SocketTimeoutException -> "SocketTimeoutException"
    is ConnectException -> "ConnectException"
    is NoRouteToHostException -> "NoRouteToHostException"
    is SocketException -> "SocketException"
    is SSLHandshakeException -> "SSLHandshakeException"
    is SSLException -> "SSLException"
    is EOFException -> "EOFException"
    is FileNotFoundException -> "FileNotFoundException"
    is ProtocolException -> "ProtocolException"
    is InterruptedIOException -> "InterruptedIOException"
    is TimeoutException -> "TimeoutException"
    is OutOfMemoryError -> "OutOfMemoryError"
    is StackOverflowError -> "StackOverflowError"
    else -> null
}
