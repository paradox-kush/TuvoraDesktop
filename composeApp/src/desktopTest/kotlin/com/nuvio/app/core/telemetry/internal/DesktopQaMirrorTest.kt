package com.nuvio.app.core.telemetry.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopQaMirrorTest {
    @Test
    fun `the property or the environment variable alone switches the mirror on, only with the value 1`() {
        assertTrue(DesktopQaMirror.isEnabled("1", null))
        assertTrue(DesktopQaMirror.isEnabled(null, "1"))
        assertFalse(DesktopQaMirror.isEnabled(null, null))
        assertFalse(DesktopQaMirror.isEnabled("true", "0"))
    }

    @Test
    fun `the log file is the one named in the docs`() {
        assertEquals("TuvoraQa.log", DesktopQaMirror.logFile().fileName.toString())
    }
}
