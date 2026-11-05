package com.nuvio.app.core.contracts

/**
 * Installed physical memory where the platform measures it honestly (desktop: the JVM's OperatingSystemMXBean,
 * registered once at startup by the platform entry point). Null = no such fact; consumers fall back to [MemoryPort]'s
 * tier. Neutral so non-fork startup code can feed it and fork features (live pause) can read it.
 */
object HostMemoryFacts {
    private var provider: (() -> Long)? = null
    fun register(provider: (() -> Long)?) { this.provider = provider }
    fun physicalMemoryBytes(): Long? = provider?.invoke()
}
