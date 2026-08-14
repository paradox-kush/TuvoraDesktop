package com.nuvio.app.features.player

import java.util.concurrent.CompletableFuture

/**
 * Process-wide ownership gate for native MPV cores.
 *
 * A lease becomes ready only after the previous lease has completed destruction. This prevents
 * Compose from initializing a replacement core while the old core still owns sockets, pipes,
 * codecs, GPU fences, or Surface buffers.
 */
internal class SerializedMpvInstanceGate {
    private val lock = Any()
    private var nextId = 1L
    private var tail = CompletableFuture.completedFuture(Unit)
    private val initializedIds = mutableSetOf<Long>()
    private var registeredCount = 0
    private var peakInitializedCount = 0

    fun register(): Lease = synchronized(lock) {
        val predecessor = tail
        val completion = CompletableFuture<Unit>()
        val lease = Lease(nextId++, predecessor, completion)
        tail = completion
        registeredCount += 1
        lease
    }

    fun snapshot(): MpvInstanceGateSnapshot = synchronized(lock) {
        MpvInstanceGateSnapshot(
            initializedInstances = initializedIds.size,
            waitingInstances = (registeredCount - initializedIds.size).coerceAtLeast(0),
            peakInitializedInstances = peakInitializedCount,
        )
    }

    inner class Lease internal constructor(
        val id: Long,
        private val predecessor: CompletableFuture<Unit>,
        private val completion: CompletableFuture<Unit>,
    ) {
        fun whenReady(action: () -> Unit) {
            predecessor.whenComplete { _, _ -> action() }
        }

        fun markInitialized() {
            synchronized(lock) {
                if (completion.isDone) return
                initializedIds += id
                peakInitializedCount = maxOf(peakInitializedCount, initializedIds.size)
            }
        }

        fun complete() {
            synchronized(lock) {
                if (completion.isDone) return
                initializedIds -= id
                registeredCount = (registeredCount - 1).coerceAtLeast(0)
                // Publish the released ownership state before unblocking the successor. A
                // synchronous whenReady callback must never observe both cores as active.
                completion.complete(Unit)
            }
        }

        fun isComplete(): Boolean = completion.isDone
    }
}

internal data class MpvInstanceGateSnapshot(
    val initializedInstances: Int,
    val waitingInstances: Int,
    val peakInitializedInstances: Int,
)

internal object AndroidMpvInstanceGate {
    val gate = SerializedMpvInstanceGate()
}
