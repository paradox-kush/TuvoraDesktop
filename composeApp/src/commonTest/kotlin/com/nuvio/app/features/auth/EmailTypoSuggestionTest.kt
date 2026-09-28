package com.nuvio.app.features.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmailTypoSuggestionTest {

    @Test
    fun `wrong or missing ending on a popular provider suggests the com domain`() {
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("gmail.con"))
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("gmail.coma"))
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("gmail.comh"))
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("gmail"))
    }

    @Test
    fun `dropped leading letters suggest the full provider`() {
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("ail.com"))
    }

    @Test
    fun `small misspellings suggest the nearest provider`() {
        assertEquals("gmail.com", EmailTypoSuggestion.suggestDomain("gmial.com"))
        assertEquals("hotmail.com", EmailTypoSuggestion.suggestDomain("hotmali.com"))
        assertEquals("yahoo.com", EmailTypoSuggestion.suggestDomain("yahooo.com"))
        assertEquals("outlook.com", EmailTypoSuggestion.suggestDomain("outlok.com"))
    }

    @Test
    fun `correct or unrelated domains get no suggestion`() {
        assertNull(EmailTypoSuggestion.suggestDomain("gmail.com"))
        assertNull(EmailTypoSuggestion.suggestDomain("yahoo.co.uk"))
        assertNull(EmailTypoSuggestion.suggestDomain("tuvora.co"))
        assertNull(EmailTypoSuggestion.suggestDomain("company.io"))
        assertNull(EmailTypoSuggestion.suggestDomain("proton.me"))
    }

    @Test
    fun `suggestEmail keeps the local part as typed`() {
        assertEquals("Kush.P@gmail.com", EmailTypoSuggestion.suggestEmail("Kush.P@gmail.con"))
    }

    @Test
    fun `suggestEmail needs a local part and an at sign`() {
        assertNull(EmailTypoSuggestion.suggestEmail("kush"))
        assertNull(EmailTypoSuggestion.suggestEmail("@gmail.con"))
    }
}
