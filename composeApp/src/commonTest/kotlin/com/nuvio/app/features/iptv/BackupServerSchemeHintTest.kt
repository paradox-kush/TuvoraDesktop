package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX92 — a backup that differs from the main server only by http vs https is accepted (it IS a
 * different endpoint, so it stays valid), but the form now says so: it is usually the same server
 * typed twice, and a dead main stays dead over the other scheme more often than not.
 */
class BackupServerSchemeHintTest {

    @Test
    fun `a backup that only swaps the scheme of the main server is flagged`() {
        assertEquals(
            setOf(1),
            BackupServerValidation.schemeOnlyDifferences(
                SOURCE_TYPE_XTREAM,
                main = "https://dead.invalid:8080",
                entries = listOf("http://other.example", "dead.invalid:8080", "  "),
            ),
        )
    }

    @Test
    fun `an M3U backup compares path and query too`() {
        assertEquals(
            setOf(0),
            BackupServerValidation.schemeOnlyDifferences(
                SOURCE_TYPE_M3U_URL,
                main = "https://dead.invalid/list.m3u",
                entries = listOf("http://DEAD.invalid/list.m3u/", "http://dead.invalid/other.m3u"),
            ),
        )
    }

    @Test
    fun `an exact duplicate or a different host is not a scheme-only difference`() {
        assertEquals(
            emptySet(),
            BackupServerValidation.schemeOnlyDifferences(
                SOURCE_TYPE_STALKER,
                main = "http://portal.example/c/",
                entries = listOf("http://portal.example", "https://portal2.example", "ftp://portal.example"),
            ),
        )
        assertEquals(emptySet(), BackupServerValidation.schemeOnlyDifferences(SOURCE_TYPE_M3U_FILE, "", listOf("http://x")))
    }

    @Test
    fun `the default ports of each scheme do not hide the match`() {
        assertEquals(
            setOf(0),
            BackupServerValidation.schemeOnlyDifferences(SOURCE_TYPE_XTREAM, "https://panel.example:443", listOf("http://panel.example:80")),
        )
    }
}
