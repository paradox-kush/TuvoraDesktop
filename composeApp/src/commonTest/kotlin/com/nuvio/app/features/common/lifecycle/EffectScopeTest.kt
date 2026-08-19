package com.nuvio.app.features.common.lifecycle

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance tests for the Phase 0 EffectScope (rules doc Rule 3). Must pass on BOTH runners
 * (testAndroidHostTest + iosSimulatorArm64Test) — the concurrency test in particular is the one that
 * only proves anything on Native. No commas in backticked names (Kotlin/Native restriction).
 */
class EffectScopeTest {

    private fun scope(onFail: (Throwable) -> Unit = { throw AssertionError("unexpected revert failure: $it") }) =
        EffectScope(onRevertFailure = onFail)

    @Test
    fun `reverts run in LIFO order`() {
        val order = mutableListOf<Int>()
        val s = scope()
        s.onRevert { order.add(1) }
        s.onRevert { order.add(2) }
        s.onRevert { order.add(3) }
        s.dispose()
        assertEquals(listOf(3, 2, 1), order, "LIFO")
    }

    @Test
    fun `a throwing revert does not abort the remaining reverts`() {
        val ran = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val s = EffectScope(onRevertFailure = { failures.add(it) })
        s.onRevert { ran.add(1) }
        s.onRevert { throw IllegalStateException("boom") }
        s.onRevert { ran.add(3) }
        s.dispose()
        assertEquals(listOf(3, 1), ran, "both non-throwing reverts ran despite the middle throw")
        assertEquals(1, failures.size, "the throw was reported to the sink")
    }

    @Test
    fun `register after dispose runs the revert immediately and the handle is inert`() {
        val s = scope()
        s.dispose()
        var ran = 0
        val handle = s.onRevert { ran++ }
        assertEquals(1, ran, "late revert ran immediately (contract 2)")
        handle.dispose()
        assertEquals(1, ran, "the returned handle is inert")
    }

    @Test
    fun `an early-disposed handle fires exactly once`() {
        var ran = 0
        val s = scope()
        val handle = s.onRevert { ran++ }
        handle.dispose()
        assertEquals(1, ran, "early dispose ran the revert")
        handle.dispose()
        assertEquals(1, ran, "second handle dispose is a no-op")
        s.dispose()
        assertEquals(1, ran, "scope dispose does not re-run an already-reverted effect")
    }

    @Test
    fun `dispose twice is a no-op`() {
        var ran = 0
        val s = scope()
        s.onRevert { ran++ }
        s.dispose()
        s.dispose()
        assertEquals(1, ran)
    }

    @Test
    fun `a revert may register a late revert during teardown`() {
        val ran = mutableListOf<String>()
        val s = scope()
        s.onRevert {
            ran.add("outer")
            s.onRevert { ran.add("late") }   // registered mid-teardown; contract 2 runs it now
        }
        s.dispose()
        assertTrue("late" in ran, "a revert registered during teardown still ran")
        assertTrue("outer" in ran)
    }

    @Test
    fun `acquire disposed before registration self-reverts`() = runBlocking {
        val s = scope()
        var reverted = false
        s.dispose()   // disposed before the acquire registers -> contract 2 land-then-revert
        val value = s.acquire(acquire = { 42 }, revert = { reverted = true })
        assertEquals(42, value, "acquire still returns its resource")
        assertTrue(reverted, "the fresh resource self-reverted")
    }

    @Test
    fun `concurrent onRevert does not corrupt the deque`() = runBlocking {
        val n = 500
        val counter = atomic(0)
        val s = scope()
        (1..n).map { async(Dispatchers.Default) { s.onRevert { counter.incrementAndGet() } } }.awaitAll()
        s.dispose()
        assertEquals(n, counter.value, "every concurrently-registered revert ran exactly once")
    }
}
