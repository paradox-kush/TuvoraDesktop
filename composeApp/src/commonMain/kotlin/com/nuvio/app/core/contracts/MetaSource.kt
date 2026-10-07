package com.nuvio.app.core.contracts

import com.nuvio.app.features.details.MetaDetails

/**
 * Firewall port for native own-source metadata + stream registration, consumed by MetaDetailsRepository.
 * The fork owns the Xtream/Stalker/M3U detail build; the shared details repo keeps only its own
 * UI-state management and delegates the native-meta short-circuit here. Sources are PLURAL (see
 * [MetaSourceRegistry]); with none registered: not-handled / null / false, so a build without an own
 * source has no native-meta lane.
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

/** Every registered native-meta provider, in registration order; a duplicate name is refused. */
object MetaSourceRegistry {
    private val providers = NamedRegistry<MetaSourceProvider>("MetaSourceProvider")

    fun register(name: String, provider: MetaSourceProvider) = providers.register(name, provider)

    val all: List<MetaSourceProvider> get() = providers.all

    internal fun resetForTest() = providers.resetForTest()
}

/** Plural view behind the single-provider interface: id-keyed calls go to the first provider that handles the id. */
class CompositeMetaSourceProvider(
    private val providers: () -> List<MetaSourceProvider>,
) : MetaSourceProvider {
    override fun handlesId(id: String): Boolean = providers().any { it.handlesId(id) }

    override suspend fun buildNativeMeta(id: String): MetaDetails? =
        providers().firstOrNull { it.handlesId(id) }?.buildNativeMeta(id)

    override suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean, forceMint: Boolean): Boolean =
        providers().firstOrNull { it.handlesId(id) }?.ensureStreamRegistered(id, forceFresh, forceMint) ?: false
}

/** Thin read facade (call sites do not churn): the combined view of [MetaSourceRegistry]. */
object MetaSourceAccess {
    private val composite = CompositeMetaSourceProvider { MetaSourceRegistry.all }

    /** The combined provider - not-handled until a source registers. Stable instance. */
    fun current(): MetaSourceProvider = composite

    fun resetForTest() = MetaSourceRegistry.resetForTest()
}
