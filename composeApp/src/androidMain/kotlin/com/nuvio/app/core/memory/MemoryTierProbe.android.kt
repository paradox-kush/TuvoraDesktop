package com.nuvio.app.core.memory

import android.app.ActivityManager
import android.content.Context

/**
 * Android's honest self-measurement: ActivityManager's isLowRamDevice + memoryClass — the
 * OS's own words for "how much heap should you want". Cached after the first read (the
 * answer never changes within a process) and mirrored into [AppMemory] as the base tier.
 */
internal object AndroidMemoryTierProbe {

    @Volatile
    private var cached: MemoryTier? = null

    fun tier(context: Context): MemoryTier = cached ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // A null ActivityManager never happens in practice; not-low is the existing
        // default (the old raw check also fell to the bigger cache on null).
        val tier = if (am == null) MemoryTier.HIGH
        else MemoryTierPolicy.androidTier(am.isLowRamDevice, am.memoryClass)
        cached = tier
        AppMemory.setBaseTier(tier)
        tier
    }
}
