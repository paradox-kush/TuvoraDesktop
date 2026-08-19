package com.nuvio.app.core.memory

import com.nuvio.app.core.contracts.MemoryPort
import com.nuvio.app.core.contracts.MemoryTier

/**
 * Fork adapter: exposes [AppMemory] + its [BudgetRegistry] through the neutral [MemoryPort].
 * Registered once at init (FeatureWiring). The dynamic pressure half stays on AppMemory directly
 * (iOSApp.swift calls AppMemory.onPressure/onRelax by name).
 */
internal object MemoryPortImpl : MemoryPort {
    override fun baseTier(): MemoryTier = AppMemory.baseTier()
    override fun setBaseTier(tier: MemoryTier) = AppMemory.setBaseTier(tier)
    override fun registerBudget(name: String, capBytes: Long, priority: Int, trim: () -> Unit) {
        AppMemory.registry.register(name, capBytes, priority, trim)
    }
    override fun trimCaches() = AppMemory.trimCaches()
}
