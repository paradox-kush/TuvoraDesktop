package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.HomeCatalogSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Neutral own-source search port (seam: search firewall). Returns shared HomeCatalogSection rows.
 * Sources are PLURAL: each registers in [SearchProviderRegistry] and carries its own [isEnabled] gate;
 * Search reads the combined view from [IptvSearchAccess].
 */
interface IptvSearchProvider {
    /**
     * True while this source can contribute search rows (IPTV: at least one enabled playlist; a media
     * server: signed in and enabled). A disabled provider is never searched and adds nothing to the
     * request key. May load the source's own state on first use.
     */
    fun isEnabled(): Boolean

    suspend fun search(query: String): List<HomeCatalogSection>

    /**
     * UX15: an opaque fingerprint of everything that changes what [search] can return — which
     * playlists are enabled, their content types and category selections, and the channels and
     * groups the viewer hid. Null while no playlist is enabled. Equal values mean equal results, so a
     * shown search is refreshed only when this changes. A digest: never carries credentials.
     */
    fun sourceSignature(): String?

    /** [sourceSignature] now, and again on every change (distinct values only). */
    fun sourceSignatureChanges(): Flow<String?>
}

/** Every registered search provider, in registration order; a duplicate name is refused. */
object SearchProviderRegistry {
    private val providers = NamedRegistry<IptvSearchProvider>("IptvSearchProvider")

    fun register(name: String, provider: IptvSearchProvider) = providers.register(name, provider)

    val all: List<IptvSearchProvider> get() = providers.all

    internal fun resetForTest() = providers.resetForTest()
}

/**
 * Plural view behind the single-provider interface. With exactly one provider every answer is that
 * provider's own (behaviour-identical to the old single slot, failures included). With several:
 * rows concatenate in registration order (a failing provider contributes nothing, never breaks the
 * others), signatures join, and the change flow combines.
 */
class CompositeSearchProvider(
    private val providers: () -> List<IptvSearchProvider>,
) : IptvSearchProvider {
    override fun isEnabled(): Boolean = providers().any { it.isEnabled() }

    override suspend fun search(query: String): List<HomeCatalogSection> {
        val enabled = providers().filter { it.isEnabled() }
        return when (enabled.size) {
            0 -> emptyList()
            1 -> enabled.single().search(query)
            else -> coroutineScope {
                enabled.map { provider ->
                    async {
                        try {
                            provider.search(query)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }
    }

    override fun sourceSignature(): String? = joinSignatures(providers().map { it.sourceSignature() })

    override fun sourceSignatureChanges(): Flow<String?> {
        val current = providers()
        return when (current.size) {
            0 -> flowOf(null)
            1 -> current.single().sourceSignatureChanges()
            else -> combine(current.map { it.sourceSignatureChanges() }) { signatures ->
                joinSignatures(signatures.toList())
            }.distinctUntilChanged()
        }
    }

    private fun joinSignatures(signatures: List<String?>): String? =
        signatures.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString("|")
}

/** Thin read facade (call sites do not churn): the combined view of [SearchProviderRegistry]. */
object IptvSearchAccess {
    private val composite = CompositeSearchProvider { SearchProviderRegistry.all }

    val provider: IptvSearchProvider
        get() = if (SearchProviderRegistry.all.isEmpty()) {
            error("no IptvSearchProvider registered — see FeatureContributions")
        } else {
            composite
        }

    /** The combined provider, or null before any source registers (tests, previews). */
    val providerOrNull: IptvSearchProvider? get() = if (SearchProviderRegistry.all.isEmpty()) null else composite

    /** Tests that registered a fake put the process back as they found it. */
    internal fun unregisterForTest() = SearchProviderRegistry.resetForTest()
}
