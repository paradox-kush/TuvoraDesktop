package com.nuvio.app.core.analytics


/**
 * Per-channel learned playback engine (universal-playback-design §3.7): once the recovery
 * coordinator has escalated a live channel to libmpv for a persistent backward-PTS discontinuity
 * (7TV), remember it so the NEXT open of that channel starts **directly on mpv** — the
 * freeze-then-switch is one-time per channel, not repeated on every open.
 *
 * Design decisions carried in from review pass 3:
 *  - **Keyed per-channel PER-LANE** (live vs catch-up, F6): a catch-up mux is a separate
 *    backward-PTS source and must not cross-contaminate the live entry.
 *  - **Stable-id keyed, graceful re-learn** (F7): keyed on the channel's `contentId`. Providers
 *    renumber `stream_id`s, which orphans the entry — the channel then simply re-learns with one
 *    more freeze-and-switch, so it degrades gracefully rather than mis-learning.
 *  - **Per-device** (D-U9): session-scoped in-memory here, matching the existing
 *    the existing in-memory learned-memory pattern. DataStore persistence across
 *    restarts, and account-sync of the portable (stream-level) subset, are follow-ups.
 *  - **Re-validation** (D-U8): a manual "does this channel work now?" affordance is preferred over a
 *    silent TTL that would re-freeze on a `max_connections=1` panel — deferred.
 *
 * In `core/analytics` (firewall-exempt), so both the coordinator and the player init path read it
 * without a fork-feature crossing.
 */
internal object LiveEngineMemory {

    /** The IPTV lane an entry belongs to — a channel's live feed and its catch-up recording learn
     *  independently (F6). */
    enum class Lane { LIVE, CATCHUP }

    // Main-thread-confined (init read + escalation write); a plain map keeps this multiplatform (no
    // java.util.concurrent on Kotlin/Native). NuvioTV keeps ConcurrentHashMap in its own copy.
    private val learned = mutableMapOf<String, LiveRecoveryCoordinator.Engine>()

    internal fun key(channelId: String, lane: Lane): String = "$channelId|${lane.name}"

    /** The engine this channel last had to use, or null if never learned. */
    internal fun preferredEngine(channelId: String?, lane: Lane): LiveRecoveryCoordinator.Engine? =
        channelId?.takeIf { it.isNotBlank() }?.let { learned[key(it, lane)] }

    /** Record the engine that finally worked for this channel (written on a decisive escalation). */
    internal fun remember(channelId: String?, lane: Lane, engine: LiveRecoveryCoordinator.Engine) {
        channelId?.takeIf { it.isNotBlank() }?.let { learned[key(it, lane)] = engine; onChange?.invoke() }
    }

    /** Forget a channel's learned engine — the hook a future manual re-validation (D-U8) will use. */
    internal fun forget(channelId: String?, lane: Lane) {
        channelId?.takeIf { it.isNotBlank() }?.let { if (learned.remove(key(it, lane)) != null) onChange?.invoke() }
    }

    // ── Persistence bridge (design §3.7: persisted so learnings survive restarts) ──
    // The pure cache stays here; the platform storage effect lives behind [onChange]/[restore], set by
    // a platform store. Keys are already "channelId|LANE"; only MPV entries are ever stored (EXO is
    // the unlearned default). (The mobile/desktop store impl lands with their dual-engine mechanism;
    // NuvioTV persists via SharedPreferences today.)
    /** Invoked after any change so the store can write the snapshot through to disk. */
    internal var onChange: (() -> Unit)? = null

    /** The learned entries (key → engine) for the store to persist. */
    internal fun snapshot(): Map<String, LiveRecoveryCoordinator.Engine> = HashMap(learned)

    /** Populate the cache from persisted entries at startup — BEFORE the first read. */
    internal fun restore(entries: Map<String, LiveRecoveryCoordinator.Engine>) {
        learned.putAll(entries)
    }

    /** Test-only reset. */
    internal fun clearAll() = learned.clear()
}
