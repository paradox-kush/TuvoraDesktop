package com.nuvio.app.features.iptv

/**
 * B64 device pass T1 — which saved ids of an M3U playlist still need checking against its catalog.
 *
 * The re-key used to run only after an ingest or a profile load, so an old-id row that reached the
 * device later (a remote pull racing the re-key's server delete, a delete that never went out, a
 * not-yet-updated phone re-pushing it) stayed as a second Continue Watching card whose id the catalog no
 * longer has — an empty page. [M3uIdRekeyRunner] now re-runs whenever the saved ids change; this ledger
 * keeps that free when nothing new arrived (no catalog read) and makes a re-appearing old id be checked
 * again. Pure bookkeeping, guarded by the runner's mutex.
 */
internal class M3uIdRekeyLedger {
    private val checked = HashMap<String, MutableSet<String>>()

    /** The [saved] ids not yet checked against [playlistId]'s current catalog (empty = skip the run). */
    fun toCheck(playlistId: String, saved: Set<String>): Set<String> = saved - checked[playlistId].orEmpty()

    /**
     * After a run over [saved]: every id the [plan] left alone is current (or unknown to this catalog —
     * re-reading it would not change that), and every id it moved to is current. The ids it moved AWAY
     * from stay unchecked, so a copy that comes back is moved again.
     */
    fun settle(playlistId: String, saved: Set<String>, plan: M3uIdRekey.Plan) {
        val set = checked.getOrPut(playlistId) { HashSet() }
        saved.forEach { if (it !in plan.renames && it !in plan.promoted) set += it }
        set += plan.renames.values
        plan.promoted.values.forEach { set += it.seriesId; set += it.episodeId }
    }

    /** The playlist's catalog was rebuilt: check everything again. */
    fun forget(playlistId: String) {
        checked.remove(playlistId)
    }

    /** Profile switch: another profile's saved ids. */
    fun clear() = checked.clear()
}
