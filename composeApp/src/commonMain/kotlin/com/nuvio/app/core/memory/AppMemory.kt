package com.nuvio.app.core.memory

import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * App-wide memory state: the base [MemoryTier] (set once by the platform probe at startup),
 * the transient pressure escalation over it, and the [BudgetRegistry] every in-memory cache
 * registers with.
 *
 * Public because the iOS host feeds pressure in from Swift (DispatchSource.memoryPressure in
 * iOSApp.swift — os_proc_available_memory is not reachable from Kotlin/Native without a
 * cinterop, verified against the 2.3.0 platform klibs). Android has NO foreground pressure
 * signal since 14: only TRIM_MEMORY_UI_HIDDEN/BACKGROUND still fire, and those are wired to
 * [trimCaches] from NuvioApplication. Desktop has no pressure source worth trusting.
 */
object AppMemory {

    internal val registry = BudgetRegistry()

    private val lock = SynchronizedObject()
    private val origin = TimeSource.Monotonic.markNow()
    private var base = MemoryTier.HIGH
    private var governor = MemoryPressureGovernor(base)

    private fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    /** Called once by the platform probe at startup; replaces the governor's base. */
    internal fun setBaseTier(tier: MemoryTier) = synchronized(lock) {
        if (tier != base) {
            base = tier
            governor = MemoryPressureGovernor(tier)
        }
    }

    /** The probe's resting tier — sizes fixed-at-creation budgets (image caches, buffers). */
    internal fun baseTier(): MemoryTier = synchronized(lock) { base }

    /**
     * The tier consumers should size NEW work by right now: base, dropped one level while
     * a pressure escalation holds (anti-flap semantics pinned in MemoryTierPolicyTest).
     */
    internal fun effectiveTier(): MemoryTier = synchronized(lock) { governor.effectiveTier(nowMs()) }

    /**
     * A platform pressure signal (iOS: DispatchSource warning/critical). Trims every
     * registered cache immediately — truth is on disk, so trimming on the FIRST sample is
     * safe — while the effective tier only escalates on the governor's 2+-consecutive rule.
     */
    fun onPressure() {
        synchronized(lock) { governor.onPressure(nowMs()) }
        registry.trimAll()
    }

    /** The platform's all-clear (iOS: DispatchSource normal). Never relaxes the hold early. */
    fun onRelax() = synchronized(lock) { governor.onRelax(nowMs()) }

    /** Drops every registered cache (Android UI_HIDDEN/BACKGROUND trim; iOS pressure). */
    internal fun trimCaches() = registry.trimAll()
}
