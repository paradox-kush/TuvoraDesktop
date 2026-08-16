package com.nuvio.app.features.iptv

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Newest-first, bounded backlog for per-tile now/next fetches.
 *
 * Field report (S24, 2026-08-16, verified against STBEmu on the same portal): every tile that ever
 * scrolled into view fired its own `ensureEpg` launch and held a place in line, so after a hard
 * scroll the tiles on screen NOW waited ~30 s behind hundreds that were long gone — while STBEmu,
 * which fetches only what a MAG box shows, navigates the same portal instantly. The portal was
 * never slow; the request volume was ours.
 *
 * The rule both references converge on (iptvnator's preview queue: "request count must track user
 * engagement, not render size"; our own PosterEnricher's front-replace): the tile the user just
 * revealed runs next, the backlog is hard-capped, and the OLDEST pending tile ages out — with
 * notice, so callers can release their once-only guard and a revisit fetches it after all.
 */
internal class TileEpgBacklog(private val cap: Int) {
    private val pending = ArrayDeque<String>()   // newest at the head

    /** Puts [key] at the front (re-adding moves it). Returns the evicted oldest key, if any. */
    fun addFront(key: String): String? {
        pending.remove(key)
        pending.addFirst(key)
        return if (pending.size > cap) pending.removeLast() else null
    }

    /** The next key to fetch — always the newest still pending. */
    fun next(): String? = pending.removeFirstOrNull()

    val size: Int get() = pending.size
}

/**
 * The process-wide tile-EPG worker: [TileEpgBacklog] ordering, two workers — the same polite
 * ceiling as the Stalker session gate and the PosterEnricher, so a scroll can never again turn
 * into an unbounded parallel fan-out against a per-IP-metered host.
 *
 * Provider-agnostic on purpose: Stalker tile fetches were at least serialized by the session gate,
 * but Xtream/M3U ones ran completely ungated, which is why switching to a DIFFERENT playlist on a
 * shared host still crawled behind the old playlist's storm.
 */
internal object TileEpgQueue {

    private class Entry(val fetch: suspend () -> Unit, val onEvicted: () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val backlog = TileEpgBacklog(BACKLOG_CAP)
    private val entries = HashMap<String, Entry>()
    private var workers = 0

    /**
     * Queues [fetch] for [key], newest first. If the backlog overflows, the evicted (oldest)
     * entry's [onEvicted] runs so its caller can forget it was ever requested.
     */
    fun enqueue(key: String, onEvicted: () -> Unit, fetch: suspend () -> Unit) {
        val evicted = synchronized(lock) {
            entries[key] = Entry(fetch, onEvicted)
            val out = backlog.addFront(key)?.let { entries.remove(it) }
            repeat(WORKERS - workers) {
                workers++
                scope.launch { drain() }
            }
            out
        }
        evicted?.onEvicted?.invoke()
    }

    private suspend fun drain() {
        while (true) {
            val entry = synchronized(lock) {
                val key = backlog.next()
                val e = key?.let { entries.remove(it) }
                if (e == null) {
                    workers--
                    return
                }
                e
            }
            // Failures are the fetch's own business (ensureEpg already swallows them); a throw
            // here must not kill the worker on Kotlin/Native (unhandled launch = SIGABRT).
            runCatching { entry.fetch() }
        }
    }

    /** Two screens' worth of tiles — enough that visible work always survives, small enough that
     *  an abandoned scroll drains in seconds, not half a minute. */
    private const val BACKLOG_CAP = 48
    private const val WORKERS = 2
}
