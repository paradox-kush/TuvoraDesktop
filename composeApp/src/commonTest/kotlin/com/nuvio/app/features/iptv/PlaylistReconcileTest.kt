package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B24 §4 — the pure reconcile + pull-classification core. Proves the conflict path preserves the
 * user's intent onto the authoritative server baseline (no wipe, no resurrection) and that remote
 * absence is only ever inferred from a SUCCESSFUL, genuinely-empty read.
 */
class PlaylistReconcileTest {

    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    @Test
    fun `reset then add-C onto a server holding A and B preserves A and B`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Add(acc("C"))))
        assertEquals(listOf("A", "B", "C"), result.accounts.map { it.id }, "add-C reconciles to A,B,C — not a wipe")
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `a delete is not resurrected by the baseline`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Delete("A")))
        assertEquals(listOf("B"), result.accounts.map { it.id }, "delete-A stays deleted after reconcile")
    }

    @Test
    fun `an edit is re-applied to the matching baseline row`() {
        val baseline = listOf(acc("A"), acc("B", name = "Old"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Update(acc("B", name = "New"))))
        assertEquals("New", result.accounts.first { it.id == "B" }.name, "the edit re-applies onto the server row")
        assertEquals(2, result.accounts.size)
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `an edit of a server-deleted row is dropped and surfaced not resurrected`() {
        val baseline = listOf(acc("A")) // B was deleted on another device
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Update(acc("B", name = "New"))))
        assertEquals(listOf("A"), result.accounts.map { it.id }, "the deleted row is not re-added")
        assertEquals(listOf("B"), result.droppedUpdateIds, "the dropped edit is surfaced to the user")
    }

    @Test
    fun `multiple ops replay in order`() {
        val baseline = listOf(acc("A"), acc("B"))
        val result = reconcilePendingOntoBaseline(
            baseline,
            listOf(PendingPlaylistOp.Add(acc("C")), PendingPlaylistOp.Delete("A"), PendingPlaylistOp.Update(acc("B", name = "B2"))),
        )
        assertEquals(listOf("B", "C"), result.accounts.map { it.id }.sorted(), "A removed, C added")
        assertEquals("B2", result.accounts.first { it.id == "B" }.name)
    }

    @Test
    fun `add of an existing id upserts rather than duplicating`() {
        val baseline = listOf(acc("A", name = "Old"))
        val result = reconcilePendingOntoBaseline(baseline, listOf(PendingPlaylistOp.Add(acc("A", name = "New"))))
        assertEquals(1, result.accounts.size, "no duplicate id")
        assertEquals("New", result.accounts.single().name)
    }

    // --- B60: replace (id-changing edit) + field-level merge ----------------------------------------

    @Test
    fun `a replace swaps the old id for the new one in place`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A"), acc("B")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals(listOf("A2", "B"), result.accounts.map { it.id }, "old id removed, new id at its position")
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `a replace whose old row was deleted elsewhere is kept as the user's add`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("B")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals(listOf("B", "A2"), result.accounts.map { it.id }, "the user typed this playlist; it is not dropped")
    }

    @Test
    fun `a replace of the only playlist never yields an empty set`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals(listOf("A2"), result.accounts.map { it.id })
    }

    @Test
    fun `an update with a base applies only the fields the user changed`() {
        val base = acc("A")
        val server = base.copy(userAgent = "X", name = "Server name")
        val edited = base.copy(autoRefreshHours = 6)
        val merged = mergeEditOntoServer(server, base, edited)
        assertEquals(6, merged.autoRefreshHours, "the edited field wins")
        assertEquals("X", merged.userAgent, "an untouched field keeps the server's value")
        assertEquals("Server name", merged.name, "an untouched field keeps the server's value")
    }

    @Test
    fun `an update without a base replaces the whole row - older pending logs`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A").copy(userAgent = "X")),
            listOf(PendingPlaylistOp.Update(acc("A", name = "New"))),
        )
        assertEquals("New", result.accounts.single().name)
        assertEquals(null, result.accounts.single().userAgent, "no base = legacy whole-row semantics")
    }

    // --- B60: pending-op collapse rules for replace -------------------------------------------------

    @Test
    fun `a replace is recorded as one op carrying the old id`() {
        val ops = emptyList<PendingOpDto>().recordReplace("A", acc("A2"), base = acc("A"))
        assertEquals(1, ops.size)
        assertEquals("replace", ops.single().kind)
        assertEquals("A", ops.single().oldId)
        assertEquals("A2", ops.single().id)
    }

    @Test
    fun `a replace of a locally added playlist stays an add`() {
        val ops = emptyList<PendingOpDto>().recordAdd(acc("A")).recordReplace("A", acc("A2"), base = acc("A"))
        assertEquals(listOf("add" to "A2"), ops.map { it.kind to it.id })
    }

    @Test
    fun `a replace chain collapses to the first old id and the first base`() {
        val ops = emptyList<PendingOpDto>()
            .recordReplace("A", acc("B"), base = acc("A"))
            .recordReplace("B", acc("C"), base = acc("B"))
        assertEquals(1, ops.size)
        assertEquals("A", ops.single().oldId)
        assertEquals("C", ops.single().id)
        assertEquals("A", ops.single().base?.id, "the base stays the last-synced row")
    }

    @Test
    fun `deleting a replaced playlist deletes the old id`() {
        val ops = emptyList<PendingOpDto>().recordReplace("A", acc("B"), base = acc("A")).recordDelete("B")
        assertEquals(listOf("delete" to "A"), ops.map { it.kind to it.id })
    }

    @Test
    fun `an update after a replace keeps the replace`() {
        val ops = emptyList<PendingOpDto>()
            .recordReplace("A", acc("B"), base = acc("A"))
            .recordUpdate(acc("B", name = "Renamed"), base = acc("B"))
        assertEquals(1, ops.size)
        assertEquals("replace", ops.single().kind)
        assertEquals("A", ops.single().oldId)
        assertEquals("Renamed", ops.single().account?.name)
    }

    @Test
    fun `an older pending log without base or old id still decodes`() {
        val raw = """{"revision":3,"pending":[{"kind":"update","id":"A","account":{"id":"A","name":"P","baseUrl":"http://A","username":"u","password":"p"}}]}"""
        val ops = decodePlaylistSyncState(raw).pending.toOps()
        assertEquals(listOf<PendingPlaylistOp>(PendingPlaylistOp.Update(acc("A"))), ops)
    }

    // --- pull classification: never infer absence from a failure -----------------------------------

    @Test
    fun `a failed pull is indeterminate regardless of the values`() {
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(succeeded = false, accounts = emptyList(), revision = 0))
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(succeeded = false, accounts = listOf(acc("A")), revision = 5))
        assertFalse(classifyPlaylistPull(false, emptyList(), 0).permitsFreshCreation(), "a timeout/permission error must never authorize a fresh create")
    }

    @Test
    fun `a successful empty read at revision zero is authoritatively absent`() {
        val outcome = classifyPlaylistPull(succeeded = true, accounts = emptyList(), revision = 0)
        assertEquals(PlaylistPullOutcome.AuthoritativeAbsent, outcome)
        assertTrue(outcome.permitsFreshCreation(), "only a proven-empty server permits a fresh create")
    }

    @Test
    fun `a successful read with rows is present and does not permit a blind create`() {
        val outcome = classifyPlaylistPull(succeeded = true, accounts = listOf(acc("A")), revision = 3)
        assertTrue(outcome is PlaylistPullOutcome.Present)
        assertEquals(3L, (outcome as PlaylistPullOutcome.Present).revision)
        assertFalse(outcome.permitsFreshCreation(), "a populated server must be reconciled onto, never blind-created over")
    }

    @Test
    fun `rows present at revision zero are still Present not Absent - legacy-seeded rows`() {
        // A legacy write can seed rows without advancing the revision; that is a populated server,
        // never an absence.
        val outcome = classifyPlaylistPull(succeeded = true, accounts = listOf(acc("A")), revision = 0)
        assertTrue(outcome is PlaylistPullOutcome.Present, "rows at rev 0 are Present, not Absent")
        assertFalse(outcome.permitsFreshCreation())
    }
}
