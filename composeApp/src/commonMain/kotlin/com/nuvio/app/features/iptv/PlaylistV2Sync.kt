package com.nuvio.app.features.iptv

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOp
import com.nuvio.app.features.mediaserver.api.MediaServerPendingOps
import com.nuvio.app.features.mediaserver.api.MediaServerSyncBinding
import com.nuvio.app.features.mediaserver.api.MediaServerSyncCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * B24 — the real app-level v2 sync engine (optimistic-concurrency revision contract). This is the
 * shipping integration, not a test-only path: [XtreamAccountSyncService] drives it through a
 * [PlaylistSyncTransport] that calls the actual `sync_pull/push_iptv_playlists_v2` RPCs, and it is
 * activated only under a debug configuration until the backend is deployed (production stays v1).
 *
 * Guarantees implemented here (each has a unit test in PlaylistV2SyncEngineTest):
 *  - Pull the authoritative collection AND its revision together, then reconcile pending local ops
 *    onto it BY INTENT (reset → add C over server [A,B] = [A,B,C]) before pushing — never a blind
 *    overwrite, never a set-union that resurrects a deleted row.
 *  - Push with the expected revision, a stable mutation id, and explicit delete-all intent.
 *  - Retain the mutation id across retries AND restarts (persisted in the sync state); rotate it only
 *    after a commit — so a "commit succeeded, response lost" retry dedups server-side.
 *  - On conflict, re-reconcile the SAME pending ops onto the server's newer rows and re-push (bounded
 *    loop) — pending user intent is never silently discarded.
 *  - A failed/unavailable pull is Indeterminate: abort (no push, no wipe) — never inferred as empty.
 *  - A failed push NEVER falls back to the destructive v1 full-replace.
 *  - Acknowledge (clear) pending ops only after the server confirms the commit that carried them.
 */

/** A durable, per-profile pending op (collapsed to the latest intent per id), serialized to storage. */
@Serializable
internal data class PendingOpDto(
    val kind: String,                 // "add" | "update" | "replace" | "delete"
    val id: String,
    val account: XtreamAccount? = null,
    /** The row as last synced, before this device's edit — the field-level merge's reference (B60).
     *  Additive: logs written by older builds decode with null (whole-row semantics). */
    val base: XtreamAccount? = null,
    /** For "replace" only: the id the edited playlist had before its URL/username/MAC changed. */
    val oldId: String? = null,
)

/** The persisted v2 sync state for one profile — survives the accounts-store corruption reset because
 *  it lives under its own storage key. */
@Serializable
internal data class PlaylistSyncState(
    val revision: Long = 0,
    val mutationId: String? = null,
    /** The payload [mutationId] was minted for ([PlaylistMutationIdPolicy.fingerprint]). Null for an
     *  id persisted by a build that stored the id alone — such an id is never reused. */
    val mutationFingerprint: String? = null,
    val pending: List<PendingOpDto> = emptyList(),
    /** True once the reconciled set is a deliberate empty (a user delete-all), so the push carries
     *  the explicit delete-all intent rather than being rejected as an accidental empty. */
    val deleteAllIntent: Boolean = false,
    /** The profile-lifetime generation this state (and its pending ops) is anchored to. Bumped by the
     *  backend on a profile deletion; when a pull reports a newer generation, the pending ops belong to
     *  a now-dead profile lifetime and are discarded rather than replayed onto the recreated profile. */
    val generation: Long = 0,
    /**
     * Wave 3: the pending intent on this profile's Jellyfin/Emby SERVER ENTRIES. They ride the SAME engine and
     * revision counter as the playlists (a second sync loop would conflict with this one on every push).
     * Additive: state written by an older build decodes with an empty list.
     */
    val mediaServerPending: List<MediaServerPendingOp> = emptyList(),
)

private fun PendingOpDto.toOp(): PendingPlaylistOp? = when (kind) {
    "add" -> account?.let { PendingPlaylistOp.Add(it) }       // a locally-created playlist: always (re)added
    "update" -> account?.let { PendingPlaylistOp.Update(it, base) } // an edit of an existing one: dropped if the server row is gone
    "replace" -> account?.let { acc -> oldId?.let { PendingPlaylistOp.Replace(it, acc, base) } } // id-changing edit (B60)
    "delete" -> PendingPlaylistOp.Delete(id)
    else -> null
}

internal fun List<PendingOpDto>.toOps(): List<PendingPlaylistOp> = mapNotNull { it.toOp() }

/** The user ADDED a new playlist locally (reset → add-C). An add survives reconcile onto any baseline. */
internal fun List<PendingOpDto>.recordAdd(account: XtreamAccount): List<PendingOpDto> =
    filterNot { it.id == account.id } + PendingOpDto("add", account.id, account)

/** The user EDITED an existing playlist. Kept as an add if it was locally created this session (so it
 *  still survives), else an update (dropped on reconcile if the server row was deleted elsewhere). */
internal fun List<PendingOpDto>.recordUpdate(account: XtreamAccount, base: XtreamAccount? = null): List<PendingOpDto> {
    val existing = firstOrNull { it.id == account.id }
    val entry = when (existing?.kind) {
        "add" -> PendingOpDto("add", account.id, account)
        // Still the same pending replace — only the edited row moves on; its old id and base stay.
        "replace" -> existing.copy(account = account)
        // Keep the FIRST base: it is the row as last synced, so every field edited since is applied.
        "update" -> PendingOpDto("update", account.id, account, base = existing.base)
        else -> PendingOpDto("update", account.id, account, base = base)
    }
    return filterNot { it.id == account.id } + entry
}

/**
 * B60 — the user edited a playlist's URL / username / MAC, so its id changed from [oldId] to
 * [account]'s. Recorded as ONE replace op (never a delete + update: that pair deleted the playlist,
 * and for the only one pushed a delete-all). Collapse rules: a replace of a local add is an add of the
 * new row; a chain A→B→C is Replace(A, C) with A's base; a pending update of [oldId] folds in.
 */
internal fun List<PendingOpDto>.recordReplace(oldId: String, account: XtreamAccount, base: XtreamAccount? = null): List<PendingOpDto> {
    if (oldId == account.id) return recordUpdate(account, base)
    val existing = firstOrNull { it.id == oldId }
    val rest = filterNot { it.id == oldId || it.id == account.id }
    val entry = when (existing?.kind) {
        "add" -> PendingOpDto("add", account.id, account)
        "replace" -> PendingOpDto("replace", account.id, account, base = existing.base, oldId = existing.oldId)
        "update" -> PendingOpDto("replace", account.id, account, base = existing.base ?: base, oldId = oldId)
        else -> PendingOpDto("replace", account.id, account, base = base, oldId = oldId)
    }
    // A→B→A lands back on the original id: that is just an edit of A.
    if (entry.kind == "replace" && entry.oldId == entry.id) {
        return rest + PendingOpDto("update", account.id, account, base = entry.base)
    }
    return rest + entry
}

/** The user REMOVED a playlist. A row that was only ever added locally this session collapses to
 *  nothing (net no-op); a row that is a pending replace deletes the id the server knows (its old id);
 *  otherwise a delete is recorded so the reconcile removes it from the server set. */
internal fun List<PendingOpDto>.recordDelete(id: String): List<PendingOpDto> {
    val existing = firstOrNull { it.id == id }
    val rest = filterNot { it.id == id }
    return when {
        existing?.kind == "add" -> rest
        existing?.kind == "replace" && existing.oldId != null ->
            rest.filterNot { it.id == existing.oldId } + PendingOpDto("delete", existing.oldId)
        else -> rest + PendingOpDto("delete", id)
    }
}

/**
 * B68 — a mutation id names exactly ONE payload. The server dedups a resend of the id it last
 * committed and RAISES (SQLSTATE 22023) when that id arrives with a different body. The engine used to
 * keep the id across any failed push, so a commit whose response was lost, followed by a reconciled
 * payload that differed (a new edit, another device's change), re-sent the committed id on every sync
 * and the profile never synced again (prod 2026-09-27: 16 profiles, some wedged since 2026-09-14).
 *
 * Reuse the id only for the identical payload it was minted for; anything else — including an id
 * stored without a fingerprint by an older build — gets a fresh id. That is always safe: every push is
 * anchored to a fresh pull's revision, so a fresh id re-sending an already-committed set costs at
 * most one redundant revision, never a lost update.
 */
internal object PlaylistMutationIdPolicy {
    fun fingerprint(accounts: List<XtreamAccount>, deleteAll: Boolean, mediaServers: List<MediaServerEntry> = emptyList()): String =
        fnv1a64Hex(
            playlistPushPayload(accounts).toString() + "|" + deleteAll +
                // unchanged for a profile without server entries, so an in-flight id minted before this build still matches
                if (mediaServers.isEmpty()) "" else "|ms:" + mediaServers.mapIndexed { i, e -> MediaServerSyncCodec.syncedFingerprint(e, i) }.joinToString(","),
        )

    fun idFor(storedId: String?, storedFingerprint: String?, fingerprint: String, mint: () -> String): String =
        if (storedId != null && storedFingerprint == fingerprint) storedId else mint()

    private fun fnv1a64Hex(text: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }
}

/** The transport the engine drives — the real impl calls the v2 RPCs; tests supply a fake. */
internal interface PlaylistSyncTransport {
    /** A pull that authoritatively completed. Throws (or the caller catches) on network failure. */
    suspend fun pull(profileId: Int): PlaylistPullResponse

    suspend fun push(
        profileId: Int,
        expectedRevision: Long?,
        accounts: List<XtreamAccount>,
        deleteAll: Boolean,
        mutationId: String,
        expectedGeneration: Long?,
    ): PlaylistPushResponse

    /**
     * Wave 3: the push that also carries the profile's media-server rows (the full replace is scoped to the
     * union of the source types this client understands). A transport that knows nothing of server entries
     * keeps the default - exactly the playlist push - so every playlist-only transport and test is unchanged.
     */
    suspend fun pushWithMediaServers(
        profileId: Int,
        expectedRevision: Long?,
        accounts: List<XtreamAccount>,
        mediaServers: List<MediaServerEntry>,
        deleteAll: Boolean,
        mutationId: String,
        expectedGeneration: Long?,
    ): PlaylistPushResponse = push(profileId, expectedRevision, accounts, deleteAll, mutationId, expectedGeneration)
}

internal data class PlaylistPullResponse(
    val revision: Long,
    val accounts: List<XtreamAccount>,
    val generation: Long = 0,
    /** Step 0: ids of [accounts] that are the server's stored `playlist_key` (not a local derivation). */
    val keyedIds: Set<String> = emptySet(),
    /** Wave 3: the pulled rows of type jellyfin/emby, mapped by the second row mapper (the first drops them). */
    val mediaServers: List<MediaServerEntry> = emptyList(),
)

internal sealed interface PlaylistPushResponse {
    data class Ok(val revision: Long, val deduped: Boolean = false) : PlaylistPushResponse
    data class Conflict(
        val currentRevision: Long,
        val currentRows: List<XtreamAccount>,
        /** Step 0: ids of [currentRows] that are the server's stored `playlist_key`. */
        val currentKeyedIds: Set<String> = emptySet(),
        val currentMediaServers: List<MediaServerEntry> = emptyList(),
    ) : PlaylistPushResponse
    /** Rejected by the server for a reason that is NOT a revision conflict (e.g. empty-without-delete-all,
     *  mutation-id reuse) — surfaced, never retried blindly, never downgraded to v1. */
    data class Rejected(val reason: String) : PlaylistPushResponse
}

internal enum class PlaylistSyncOutcome {
    SYNCED,          // pushed (or nothing to push) and the baseline is current
    UP_TO_DATE,      // pulled; local already matched; nothing to push
    WITHHELD,        // not authoritative (Recovered/Corrupt/Absent with no pending intent) — server preserved
    PULL_FAILED,     // pull did not authoritatively complete — aborted, nothing wiped
    PUSH_FAILED,     // push failed after reconcile — pending ops retained for a later attempt
    CONFLICT_EXHAUSTED, // reconcile looped past the budget — pending ops retained
    REJECTED,        // server rejected the push (surfaced) — pending ops retained
}

/**
 * The pure engine. All I/O is injected, so the whole flow is unit-testable with a fake transport +
 * in-memory state, and the production wiring supplies the real RPC transport + durable storage.
 */
internal class PlaylistV2SyncEngine(
    private val transport: PlaylistSyncTransport,
    private val loadState: (Int) -> PlaylistSyncState,
    private val saveState: (Int, PlaylistSyncState) -> Unit,
    /** The current authoritative local accounts (what the user sees). */
    private val currentAccounts: () -> List<XtreamAccount>,
    /** Whether the loaded local state may drive a full set (Valid, incl. a genuine delete-all). */
    private val canPush: () -> Boolean,
    /** Apply a reconciled/pulled set as the new local state WITHOUT echoing a push back. */
    private val applyLocal: (profileId: Int, accounts: List<XtreamAccount>) -> Unit,
    /** Still targeting this profile? (a switch mid-flight must not redirect the payload). */
    private val stillActive: (Int) -> Boolean,
    private val newMutationId: () -> String,
    private val maxConflictRetries: Int = 5,
    /** The value two rows must share to count as "in sync": everything the server stores for a row. */
    private val syncedKey: (XtreamAccount) -> Any = ::playlistSyncKey,
    /**
     * Step 0 — reconciles this device's playlist ids with a pulled set BEFORE anything compares or
     * reconciles against it ([PlaylistKeyAdoption]): re-keys local ids onto server keys (moving their
     * prefix-keyed data, once) and returns the pulled rows as they should be applied. Runs before the
     * sync state is loaded, so pending ops it rewrites are the ones this sync replays.
     */
    private val adoptKeys: (profileId: Int, pulled: List<XtreamAccount>, keyedIds: Set<String>) -> PlaylistKeyAdoption.Result =
        { _, pulled, _ -> PlaylistKeyAdoption.Result(pulled, emptyList()) },
    /**
     * Wave 3 — the media-server half (design 5.3): the profile's Jellyfin/Emby entries ride this same engine.
     * Null (the default) = playlists only, behaviour identical to before.
     */
    private val mediaServers: MediaServerSyncBinding? = null,
) {
    /**
     * One full sync for [profileId]: pull authoritative rows+revision, reconcile pending intent onto
     * them, and push under the revision contract. Network calls are made OUTSIDE any storage lock
     * (this function holds none; the caller serializes per profile).
     */
    suspend fun sync(profileId: Int): PlaylistSyncOutcome {
        // 1. Pull authoritatively. A failure is Indeterminate — abort, wipe nothing.
        val rawPull = runCatching { transport.pull(profileId) }.getOrNull() ?: return PlaylistSyncOutcome.PULL_FAILED
        if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
        val pull = rawPull.copy(accounts = adoptKeys(profileId, rawPull.accounts, rawPull.keyedIds).accounts)

        var state = loadState(profileId)
        // Generation reset (B24 profile-recreation safety): if the server reports a newer generation
        // than the one our pending ops are anchored to, the profile was deleted (and possibly recreated
        // at the reused id) after those ops were recorded. They belong to a dead profile lifetime, so we
        // DISCARD them — replaying them would silently populate the recreated profile. We then adopt the
        // new generation so any genuinely new local edit is anchored correctly. Persisted immediately so
        // the discard survives a crash before the push.
        if (pull.generation > state.generation && (state.pending.isNotEmpty() || state.mediaServerPending.isNotEmpty())) {
            state = state.copy(pending = emptyList(), mediaServerPending = emptyList(), deleteAllIntent = false)
        }
        if (state.generation != pull.generation) {
            state = state.copy(generation = pull.generation)
            saveState(profileId, state)
        }
        val recorded = state.pending.toOps()
        val recordedMedia = if (mediaServers != null) state.mediaServerPending else emptyList()
        // The exact pending entries this sync will push. On commit we remove ONLY these, so an edit
        // recorded DURING the push (a newer pending entry) is preserved, never acknowledged with the
        // request that did not carry it (B24 §3).
        var ackedPending: List<PendingOpDto> = state.pending
        var ackedMedia: List<MediaServerPendingOp> = recordedMedia

        // 2. Decide the ops to replay and the starting expected revision.
        var pending: List<PendingPlaylistOp>
        var pendingMedia: List<MediaServerPendingOp> = recordedMedia
        var expected: Long?
        val localMedia = mediaServers?.currentEntries().orEmpty()
        // Without the binding the engine is playlists-only: whatever server rows the pull carried are none of its business.
        val remoteMedia = if (mediaServers != null) pull.mediaServers else emptyList()
        when {
            recorded.isNotEmpty() || recordedMedia.isNotEmpty() -> {
                pending = recorded
                expected = pull.revision
            }
            sameSyncedSet(currentAccounts(), pull.accounts, syncedKey) && sameMediaSet(localMedia, remoteMedia) -> {
                // In sync — ids AND every synced field match (B60: an id-only comparison left a field
                // changed on another device, e.g. a UA or refresh interval, never applied here).
                // Adopt the server revision, clear any stale mutation id.
                saveState(profileId, state.copy(revision = pull.revision, mutationId = null, mutationFingerprint = null))
                return PlaylistSyncOutcome.UP_TO_DATE
            }
            pull.accounts.isEmpty() && remoteMedia.isEmpty() && pull.revision == 0L &&
                ((canPush() && currentAccounts().isNotEmpty()) || (mediaServers?.canPushFullReplace?.invoke() == true && localMedia.isNotEmpty())) -> {
                // Migration / first-ever v2 write: the server has no collection and the local set is a
                // genuine authored set. Push it up as an initial creation (expected = null).
                pending = if (canPush()) currentAccounts().map { PendingPlaylistOp.Add(it) } else emptyList()
                pendingMedia = if (mediaServers?.canPushFullReplace?.invoke() == true) localMedia.map { MediaServerPendingOp("add", it.key, it) } else emptyList()
                expected = null
            }
            else -> {
                // No recorded intent and a divergence we can't attribute to the user (e.g. a damaged
                // local store, or another device's change — including a field-only change): the server
                // is authoritative — adopt it, never push local over it.
                applyLocal(profileId, pull.accounts)
                mediaServers?.applyFromRemote?.invoke(profileId, remoteMedia)
                saveState(profileId, state.copy(revision = pull.revision, pending = emptyList(), mediaServerPending = emptyList(), mutationId = null, mutationFingerprint = null, deleteAllIntent = false))
                return if (canPush()) PlaylistSyncOutcome.UP_TO_DATE else PlaylistSyncOutcome.WITHHELD
            }
        }

        // 3. Reconcile pending intent onto the authoritative server rows and push. The mutation id is
        //    reused (across retries AND restarts, since it is persisted) only for the identical payload
        //    it was minted for — see [PlaylistMutationIdPolicy].
        var baseRows = pull.accounts
        var mediaBaseline = remoteMedia
        var baselineRevision = pull.revision

        var retries = 0
        while (true) {
            if (!stillActive(profileId)) return PlaylistSyncOutcome.PULL_FAILED
            val reconciled = reconcilePendingOntoBaseline(baseRows, pending)
            applyLocal(profileId, reconciled.accounts)
            // The server entries: a damaged/absent local store carries no intent of its own, so the server's rows go
            // back unchanged (never a truncated push that would delete them); otherwise the intent replays onto them.
            val reconciledMedia = if (mediaServers == null) emptyList() else MediaServerPendingOps.reconcile(mediaBaseline, pendingMedia)
            if (mediaServers != null) mediaServers.applyFromRemote(profileId, reconciledMedia)
            val deleteAll = reconciled.accounts.isEmpty() && reconciledMedia.isEmpty()
            val fingerprint = PlaylistMutationIdPolicy.fingerprint(reconciled.accounts, deleteAll, reconciledMedia)
            val mutationId = PlaylistMutationIdPolicy.idFor(state.mutationId, state.mutationFingerprint, fingerprint, newMutationId)
            // Persist the mutation id + its payload + baseline BEFORE the network write, but re-read
            // first so a pending entry recorded since [loadState] is not clobbered by this write.
            state = loadState(profileId).copy(mutationId = mutationId, mutationFingerprint = fingerprint, revision = baselineRevision)
            saveState(profileId, state)
            val resp = runCatching {
                // Anchor the write to the generation we observed on pull; the server rejects it as
                // stale_generation if the profile was deleted since, so a reconcile-retry cannot
                // resurrect a deleted-then-recreated profile even if this client did not discard.
                if (mediaServers == null) transport.push(profileId, expected, reconciled.accounts, deleteAll, mutationId, pull.generation)
                else transport.pushWithMediaServers(profileId, expected, reconciled.accounts, reconciledMedia, deleteAll, mutationId, pull.generation)
            }.getOrElse { return PlaylistSyncOutcome.PUSH_FAILED } // keep pending + mutationId; retry later

            when (resp) {
                is PlaylistPushResponse.Ok -> {
                    // Acknowledge only now — the server confirmed the commit. Re-read the CURRENT log
                    // and remove ONLY the entries this request carried; a newer edit recorded mid-push
                    // stays pending for the next sync (B24 §3 — never ack an op not in the request).
                    val fresh = loadState(profileId)
                    saveState(profileId, fresh.copy(
                        revision = resp.revision,
                        pending = fresh.pending.filterNot { it in ackedPending },
                        mediaServerPending = fresh.mediaServerPending.filterNot { it in ackedMedia },
                        mutationId = null,
                        mutationFingerprint = null,
                        deleteAllIntent = false,
                    ))
                    return PlaylistSyncOutcome.SYNCED
                }
                is PlaylistPushResponse.Conflict -> {
                    if (retries++ >= maxConflictRetries) {
                        // Preserve pending ops for a later attempt; do NOT discard user intent.
                        saveState(profileId, state.copy(revision = resp.currentRevision))
                        return PlaylistSyncOutcome.CONFLICT_EXHAUSTED
                    }
                    val adopted = adoptKeys(profileId, resp.currentRows, resp.currentKeyedIds)
                    baseRows = adopted.accounts
                    mediaBaseline = if (mediaServers != null) resp.currentMediaServers else emptyList()
                    // A re-key here also rewrote the durable pending log; replay (and later ack) the
                    // same rewritten entries, or an edit recorded under the old id would be dropped.
                    if (adopted.rekeys.isNotEmpty()) {
                        pending = PlaylistKeyAdoption.rewriteOps(pending, adopted.rekeys)
                        ackedPending = PlaylistKeyAdoption.rewritePending(ackedPending, adopted.rekeys)
                    }
                    expected = resp.currentRevision
                    baselineRevision = resp.currentRevision
                    state = state.copy(revision = expected)
                    saveState(profileId, state)
                    // loop: re-reconcile the same pending ops onto the newer server rows
                }
                is PlaylistPushResponse.Rejected -> {
                    // Surface; retain pending. Never fall back to v1.
                    return PlaylistSyncOutcome.REJECTED
                }
            }
        }
    }
}

/** Order-independent comparison of what the server stores for each row (the payload's order is
 *  positional, not content). The id rides the wire as `playlist_key` (Step 0). */
private fun sameSyncedSet(a: List<XtreamAccount>, b: List<XtreamAccount>, key: (XtreamAccount) -> Any): Boolean =
    a.size == b.size && a.groupingBy(key).eachCount() == b.groupingBy(key).eachCount()

/** Order-independent comparison of what the server stores for each media-server row (sort order is positional). */
private fun sameMediaSet(local: List<MediaServerEntry>, remote: List<MediaServerEntry>): Boolean =
    local.size == remote.size &&
        local.map { MediaServerSyncCodec.syncedFingerprint(it, 0) }.sorted() == remote.map { MediaServerSyncCodec.syncedFingerprint(it, 0) }.sorted()

/** Everything the server stores for [account] — its push row with the positional sort order fixed. */
internal fun playlistSyncKey(account: XtreamAccount): Any = playlistPushPayload(listOf(account)).single()

internal val playlistSyncStateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodePlaylistSyncState(raw: String?): PlaylistSyncState =
    raw?.let { runCatching { playlistSyncStateJson.decodeFromString(PlaylistSyncState.serializer(), it) }.getOrNull() }
        ?: PlaylistSyncState()

internal fun encodePlaylistSyncState(state: PlaylistSyncState): String =
    playlistSyncStateJson.encodeToString(PlaylistSyncState.serializer(), state)
