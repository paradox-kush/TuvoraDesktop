package com.nuvio.app.features.common.lifecycle

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A revert handle. [dispose] runs the inverse at most once. */
fun interface Disposable {
    fun dispose()
}

/**
 * Thread-safe LIFO revert scope (Invariant T — tracked disposal). Every acquisition registers its
 * inverse via [onRevert]; [dispose] runs the inverses in reverse order. Hard contracts, each closing
 * a real failure mode observed in this codebase:
 *
 *  1. A throwing revert NEVER aborts the remaining reverts — it is reported to [onRevertFailure]
 *     (telemetry sink, provided at the root) and teardown continues. A naive `while` loop inverted
 *     the guarantee: one bad disposer leaked everything registered before it.
 *  2. Registering on an already-disposed scope runs the revert IMMEDIATELY and returns an inert
 *     handle — the resource must not outlive the scope. Never throw mid-teardown for a late arrival.
 *  3. All state is lock-guarded (atomicfu [SynchronizedObject]): repositories run scopes on
 *     Dispatchers.Default, not the composition dispatcher.
 *  4. Reverts run OUTSIDE the lock, so a revert may itself register a late revert (contract 2 drains it).
 *  5. [dispose] is NOT a completion barrier for in-flight suspend-[acquire]s: an acquire still in
 *     flight when [dispose] returns lands afterwards and self-reverts via contract 2 — the resource
 *     is briefly held post-dispose (e.g. a max_connections=1 slot). Never read [dispose]'s return as
 *     "everything is gone"; where that matters, await the owning coroutine scope first.
 */
class EffectScope(
    private val onRevertFailure: (Throwable) -> Unit,
) : Disposable {
    private val lock = SynchronizedObject()
    private val reverts = ArrayDeque<() -> Unit>()
    private var disposed = false

    /** Register an acquire's inverse; returns a handle so ONE effect can be reverted early. */
    fun onRevert(revert: () -> Unit): Disposable {
        val late = synchronized(lock) {
            if (disposed) true else { reverts.addLast(revert); false }
        }
        if (late) {                              // contract 2
            runRevert(revert)
            return Disposable { }
        }
        return Disposable {
            val mine = synchronized(lock) { reverts.remove(revert) }
            if (mine) runRevert(revert)          // fire at most once
        }
    }

    /** Adopt an existing [Disposable] (e.g. a subscription handle) into LIFO teardown. */
    fun adopt(handle: Disposable): Disposable = onRevert(handle::dispose)

    /**
     * Compensation, not inverse — network emissions (a minted single-use link, a push) cannot be
     * reverted, only compensated, and compensations do NOT inherit the LIFO reorder-safety that true
     * inverses have. This is LABELLING, not enforcement: it runs in the same stack; the discipline
     * (don't early-dispose a compensation out of order, don't assume it restored anything) is on the
     * caller, made possible by the label being visible at the call site.
     */
    fun onCompensate(compensate: () -> Unit): Disposable = onRevert(compensate)

    override fun dispose() {
        while (true) {
            val next = synchronized(lock) {
                disposed = true
                reverts.removeLastOrNull()       // LIFO, one at a time
            } ?: return
            runRevert(next)                      // outside the lock (contract 4)
        }
    }

    private fun runRevert(revert: () -> Unit) {
        try {
            revert()
        } catch (t: Throwable) {
            onRevertFailure(t)                   // contract 1 — never abort the rest
        }
    }
}

/**
 * The ONLY way to perform a suspending effectful acquire: acquire + register-inverse is ONE
 * non-cancellable unit (cancellation is NOT revert — an in-flight effect must land and have its
 * inverse harvested, never be abandoned mid-flight). SURGICAL scope only — [NonCancellable] wraps
 * the acquire+register pair, never surrounding I/O orchestration (a blanket NonCancellable
 * reintroduces the ANR class). Place cancellation checkpoints BETWEEN acquire units.
 *
 * TIMEOUT RULE: `withTimeout` does NOT fire inside [NonCancellable] (timeouts are delivered as
 * cancellation, which NonCancellable swallows). Every [acquire]'s I/O must carry ENGINE-LEVEL
 * timeouts (Ktor request/connect/socket), which fire as exceptions independent of coroutine
 * cancellation. An acquire whose I/O has no engine-level timeout can hang teardown forever — a bug.
 */
suspend fun <T> EffectScope.acquire(acquire: suspend () -> T, revert: (T) -> Unit): T =
    withContext(NonCancellable) {
        val resource = acquire()
        onRevert { revert(resource) }
        resource
    }
