package com.nuvio.app.core.telemetry.internal

import com.nuvio.app.core.telemetry.api.DiagEntry
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The rolling on-device log: a lock-guarded bounded deque (one owner). A copy is published after
 * every write so the crash handler can read the tail WITHOUT touching the lock.
 */
internal class FlightRecorder(private val capacity: Int = CAPACITY) {
    private val lock = SynchronizedObject()
    private val ring = ArrayDeque<DiagEntry>(capacity)
    private var dirty = false

    @Volatile
    private var published: List<DiagEntry> = emptyList()

    fun record(entry: DiagEntry) {
        val clean = DiagTrailPolicy.sanitize(entry) ?: return
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(clean)
            dirty = true
            published = ring.toList()
        }
    }

    fun tail(n: Int): List<DiagEntry> = published.takeLast(n)

    /** Lock-free read for the crash handler. */
    fun lastPublished(): List<DiagEntry> = published

    /** True once per change: lets the persister write only when something is new (delta-shaped). */
    fun consumeDirty(): Boolean = synchronized(lock) { val d = dirty; dirty = false; d }

    fun markDirty() = synchronized(lock) { dirty = true }

    companion object {
        const val CAPACITY = 200
    }
}
