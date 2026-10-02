package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupCodeEntryPolicyTest {
    @Test
    fun `Continue is enabled exactly at 12 valid characters`() {
        assertFalse(SetupCodeEntryPolicy.canContinue(""))
        assertFalse(SetupCodeEntryPolicy.canContinue("TUV-ABCD-EFGH"))
        assertFalse(SetupCodeEntryPolicy.canContinue("TUV-ABCD-EFGH-JKM0"), "0 is not in the alphabet")
        assertTrue(SetupCodeEntryPolicy.canContinue("TUV-ABCD-EFGH-JKMN"))
        assertTrue(SetupCodeEntryPolicy.canContinue("abcdefghjkmn"))
    }

    @Test
    fun `a bad character is flagged as it is typed, an unfinished code is not`() {
        assertNull(SetupCodeEntryPolicy.liveProblem(""))
        assertNull(SetupCodeEntryPolicy.liveProblem("TUV-ABC"))
        assertEquals(SetupCodeProblem.BAD_CHARACTERS, SetupCodeEntryPolicy.liveProblem("TUV-ABC0"))
        assertEquals(SetupCodeProblem.BAD_CHARACTERS, SetupCodeEntryPolicy.liveProblem("abcl"))
        assertNull(SetupCodeEntryPolicy.liveProblem("TUVWXYZ23456"), "a code that starts with TUV is still a code")
    }

    @Test
    fun `an edit that adds several characters is a paste`() {
        assertFalse(SetupCodeEntryPolicy.isPasteLike("TUV-AB", "TUV-ABC"))
        assertFalse(SetupCodeEntryPolicy.isPasteLike("TUV-ABC", "TUV-AB"), "backspace")
        assertTrue(SetupCodeEntryPolicy.isPasteLike("", "TUV-ABCD-EFGH-JKMN"))
        assertTrue(SetupCodeEntryPolicy.isPasteLike("TUV-A", "TUV-ABCDEF"))
    }

    @Test
    fun `a pasted code goes straight to the preview only when it is complete`() {
        assertTrue(SetupCodeEntryPolicy.submitsOnPaste("TUV-ABCD-EFGH-JKMN"))
        assertFalse(SetupCodeEntryPolicy.submitsOnPaste("TUV-ABCD-EF"))
        assertFalse(SetupCodeEntryPolicy.submitsOnPaste("hello world"))
    }
}
