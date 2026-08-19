package com.nuvio.app.core.contracts

/**
 * The neutral seam onto app-wide memory state. Fork code (core.memory: AppMemory + the
 * BudgetRegistry) implements it; shared consumers — the image loaders, the player buffer
 * sizing, the platform startup probes — size their budgets through this and never import
 * core.memory. The platform probe is the ONLY writer: it reads the OS's honest self-measurement
 * ([MemoryTierPolicy]) at startup and calls [setBaseTier] once, before any budget is created.
 *
 * The dynamic pressure half (iOS DispatchSource → AppMemory.onPressure) stays on AppMemory
 * directly because iOSApp.swift calls it by name from Swift — it is not a Kotlin-consumer seam.
 */
interface MemoryPort {
    /** The resting tier — sizes fixed-at-creation budgets (image caches, player buffers). */
    fun baseTier(): MemoryTier

    /** Called once by the platform startup probe; every later budget reads [baseTier]. */
    fun setBaseTier(tier: MemoryTier)

    /**
     * Registers an in-memory cache with the budget registry: a name, a tier-sized cap, and a
     * trim callback the pressure listener walks. [priority] orders trimming (lower = first).
     */
    fun registerBudget(name: String, capBytes: Long, priority: Int, trim: () -> Unit)

    /** Drops every registered cache (Android UI_HIDDEN/BACKGROUND trim). */
    fun trimCaches()
}

/**
 * Process-global access point, registered once at init (FeatureWiring.registerFeatureContributions).
 * The default is a no-op that reports [MemoryTier.HIGH] — the same fallback the old probe used when
 * ActivityManager was null — so tests and any not-yet-wired path stay safe.
 */
object MemoryPortAccess {
    private var port: MemoryPort = NoopMemoryPort
    fun register(port: MemoryPort) { this.port = port }
    fun current(): MemoryPort = port
}

private object NoopMemoryPort : MemoryPort {
    override fun baseTier(): MemoryTier = MemoryTier.HIGH
    override fun setBaseTier(tier: MemoryTier) {}
    override fun registerBudget(name: String, capBytes: Long, priority: Int, trim: () -> Unit) {}
    override fun trimCaches() {}
}
