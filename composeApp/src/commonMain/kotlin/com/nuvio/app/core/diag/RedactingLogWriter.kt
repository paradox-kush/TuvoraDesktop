package com.nuvio.app.core.diag

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter

/**
 * B116 — the logging chokepoint for every Kermit logger in the app. Every `Logger.withTag(...)`
 * shares the global config, so installing this once routes all Kermit output (logcat on Android,
 * os_log/NSLog on Apple, stdout on desktop) through [LogRedaction.text].
 *
 * A throwable's message and stack trace are rendered HERE and redacted too (Ktor and FFmpeg errors
 * embed the full request URL in the exception message), then passed on as text with no throwable,
 * so the delegate cannot print the raw message itself.
 */
class RedactingLogWriter(private val delegate: LogWriter) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean = delegate.isLoggable(tag, severity)

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val safeMessage = LogRedaction.text(message)
        if (throwable == null) {
            delegate.log(severity, safeMessage, tag, null)
        } else {
            delegate.log(severity, safeMessage + "\n" + LogRedaction.text(throwable.stackTraceToString()), tag, null)
        }
    }
}

/** Process-init: route all Kermit logging through [RedactingLogWriter]. Idempotent. */
fun installLogRedaction() {
    Logger.setLogWriters(RedactingLogWriter(platformLogWriter()))
}
