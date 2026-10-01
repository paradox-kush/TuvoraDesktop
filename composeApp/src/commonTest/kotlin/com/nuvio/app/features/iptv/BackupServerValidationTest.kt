package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs [BackupServerGolden] (Step 0.3) — mirrored by nuvio-web's backupServers.golden.json. */
class BackupServerValidationTest {

    @Test
    fun `golden validation table`() {
        for (case in BackupServerGolden.cases) {
            val outcome = BackupServerValidation.validate(case.sourceType, case.main, case.entries)
            assertEquals(case.expectedUrls, outcome.urls, "${case.name}: urls")
            assertEquals(case.expectedProblems, outcome.problems.map { it.index to it.problem.name }, "${case.name}: problems")
        }
    }

    @Test
    fun `backups exist for xtream and m3u links and stalker only`() {
        assertTrue(BackupServerValidation.supportsBackups(SOURCE_TYPE_XTREAM))
        assertTrue(BackupServerValidation.supportsBackups(SOURCE_TYPE_M3U_URL))
        assertTrue(BackupServerValidation.supportsBackups(SOURCE_TYPE_STALKER))
        assertFalse(BackupServerValidation.supportsBackups(SOURCE_TYPE_M3U_FILE))
    }
}

class BackupServerListEditsTest {

    @Test
    fun `add stops at five rows`() {
        var rows = emptyList<String>()
        repeat(7) { rows = BackupServerListEdits.add(rows) }
        assertEquals(5, rows.size)
        assertFalse(BackupServerListEdits.canAdd(rows))
    }

    @Test
    fun `move up and down reorder priority and ignore the edges`() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), BackupServerListEdits.moveUp(rows, 1))
        assertEquals(listOf("a", "c", "b"), BackupServerListEdits.moveDown(rows, 1))
        assertEquals(rows, BackupServerListEdits.moveUp(rows, 0))
        assertEquals(rows, BackupServerListEdits.moveDown(rows, 2))
    }

    @Test
    fun `remove and update touch only their row`() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), BackupServerListEdits.remove(rows, 1))
        assertEquals(listOf("a", "x", "c"), BackupServerListEdits.update(rows, 1, "x"))
        assertEquals(rows, BackupServerListEdits.remove(rows, 9))
    }
}
