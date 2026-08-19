package com.nuvio.app.core.contracts

import com.nuvio.app.features.details.MetaDetails

/**
 * Firewall port for native IPTV metadata + stream registration, consumed by MetaDetailsRepository.
 * The fork owns the Xtream/Stalker/M3U detail build; the shared details repo keeps only its own
 * UI-state management and delegates the native-meta short-circuit here. No-op default: not-handled /
 * null / false, so a build without IPTV has no native-meta lane.
 */
interface MetaSourceProvider {
    /** True when [id] is a namespaced IPTV id with a native (non-addon) detail. */
    fun handlesId(id: String): Boolean

    /** Build native MetaDetails for a [handlesId] id (VOD/series), or null on miss. */
    suspend fun buildNativeMeta(id: String): MetaDetails?

    /**
     * Rebuild + re-register the direct stream item for a persisted IPTV id whose registry entry was
     * lost. [forceFresh] skips the cache (Stalker single-use links); [forceMint] bypasses static-cmd.
     */
    suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean
}

object MetaSourceAccess {
    private val noOp = object : MetaSourceProvider {
        override fun handlesId(id: String) = false
        override suspend fun buildNativeMeta(id: String): MetaDetails? = null
        override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean) = false
    }
    private var provider: MetaSourceProvider? = null

    fun register(p: MetaSourceProvider) {
        provider = p
    }

    /** The registered provider, or a no-op until IPTV registers. */
    fun current(): MetaSourceProvider = provider ?: noOp

    fun resetForTest() {
        provider = null
    }
}
