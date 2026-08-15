package com.nuvio.app.core.memory

/**
 * The app-wide memory tier. One shared enum; each platform probes itself honestly
 * (Android: ActivityManager, iOS: NSProcessInfo + pressure events, desktop: always HIGH)
 * and every budget — image caches, ingest batches, player buffers — is sized from it.
 */
enum class MemoryTier {
    LOW, MID, HIGH;

    /** One tier lower (transient pressure escalation). LOW has no lower rung. */
    fun escalated(): MemoryTier = when (this) {
        LOW -> LOW
        MID -> LOW
        HIGH -> MID
    }
}
