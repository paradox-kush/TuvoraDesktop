package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden vectors for [SetupCode] — shared verbatim with nuvio-web `src/lib/providers/code.test.ts` and
 * every platform's copy of this table (contract section 1). All must hold on every platform.
 */
class SetupCodeTest {

    private fun assertValid(expected: String, input: String) =
        assertEquals(SetupCodeParse.Valid(expected), SetupCode.normalize(input), "normalize(\"$input\")")

    private fun assertInvalid(expected: SetupCodeProblem, input: String) =
        assertEquals(SetupCodeParse.Invalid(expected), SetupCode.normalize(input), "normalize(\"$input\")")

    @Test
    fun `every spelling of a code normalizes to the 12 characters`() {
        for (input in listOf(
            "TUV-ABCD-EFGH-JKMN", "tuv-abcd-efgh-jkmn", "  TUV ABCD EFGH JKMN ",
            "ABCD-EFGH-JKMN", "abcdefghjkmn", "TUVABCDEFGHJKMN",
        )) assertValid("ABCDEFGHJKMN", input)
    }

    @Test
    fun `a bare code that itself starts with TUV survives`() {
        assertValid("TUVWXYZ23456", "TUVWXYZ23456")
        assertValid("TUVWXYZ23456", "TUV-TUVW-XYZ2-3456")
    }

    @Test
    fun `characters outside the alphabet are refused before any request`() {
        for (input in listOf("TUV-ABCD-EFGH-JKM0", "TUV-ABCD-EFGH-JKMI", "ABCD'; drop table--", "ABCDEFGHJKM%")) {
            assertInvalid(SetupCodeProblem.BAD_CHARACTERS, input)
        }
    }

    @Test
    fun `nothing typed is empty`() {
        assertInvalid(SetupCodeProblem.EMPTY, "")
        assertInvalid(SetupCodeProblem.EMPTY, " - ")
    }

    @Test
    fun `the wrong number of characters is named`() {
        assertInvalid(SetupCodeProblem.WRONG_LENGTH, "ABCD-EFGH")
        assertInvalid(SetupCodeProblem.WRONG_LENGTH, "ABCDEFGHJKMNP")
    }

    @Test
    fun `format groups a valid code and leaves anything else alone`() {
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.format("abcdefghjkmn"))
        assertEquals("nonsense!", SetupCode.format("nonsense!"))
    }

    @Test
    fun `parse returns the code or null`() {
        assertEquals("ABCDEFGHJKMN", SetupCode.parse("tuv abcd efgh jkmn"))
        assertNull(SetupCode.parse("ABCD"))
    }

    @Test
    fun `live format groups what has been typed so far`() {
        assertEquals("", SetupCode.liveFormat(""))
        assertEquals("", SetupCode.liveFormat("  - "))
        assertEquals("TUV-A", SetupCode.liveFormat("a"))
        assertEquals("TUV-ABCD", SetupCode.liveFormat("abcd"))
        assertEquals("TUV-ABCD-E", SetupCode.liveFormat("abcde"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("abcdefghjkmn"))
    }

    @Test
    fun `live format never doubles a typed or pasted prefix`() {
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("TUV-ABCD-EFGH-JKMN"))
        assertEquals("TUV-AB", SetupCode.liveFormat("tuv-ab"))
        assertEquals("TUV-AB", SetupCode.liveFormat("TUV ab"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("TUVABCDEFGHJKMN"))
    }

    @Test
    fun `live format keeps a bare TUV start as code characters and caps at twelve`() {
        assertEquals("TUV-TUVW", SetupCode.liveFormat("TUVW"))
        assertEquals("TUV-TUVW-XYZ2-3456", SetupCode.liveFormat("TUVWXYZ23456"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("abcdefghjkmnpqrst"))
    }

    @Test
    fun `live format keeps a bad character so the person sees what they typed`() {
        assertEquals("TUV-AB0D", SetupCode.liveFormat("ab0d"))
        assertEquals(false, SetupCode.isComplete("ab0d"))
    }

    @Test
    fun `isComplete is true only for a full valid code`() {
        assertTrue(SetupCode.isComplete("TUV-ABCD-EFGH-JKMN"))
        assertEquals(false, SetupCode.isComplete("TUV-ABCD-EFGH"))
        assertEquals(false, SetupCode.isComplete(""))
    }

    @Test
    fun `a code is found inside a pasted link or message`() {
        assertEquals("ABCDEFGHJKMN", SetupCode.extractFromLink("https://tuvora.co/s/TUV-ABCD-EFGH-JKMN"))
        assertEquals("ABCDEFGHJKMN", SetupCode.extractFromLink("Open https://tuvora.co/s/TUV-ABCD-EFGH-JKMN?x=1 then sign in"))
        assertNull(SetupCode.extractFromLink("https://example.com/s/hello"))
        assertNull(SetupCode.extractFromLink("TUV-ABCD-EFGH-JKMN"))
    }
}
