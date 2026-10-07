package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.IptvSearchProvider
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.iptv.overlay.IptvOverlayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

internal object XtreamSearchProvider : IptvSearchProvider {
    override fun isEnabled(): Boolean {
        XtreamRepository.ensureLoaded()
        return XtreamRepository.hasEnabledAccounts()
    }

    override suspend fun search(query: String): List<HomeCatalogSection> = XtreamSearchIndex.search(query)

    override fun sourceSignature(): String? {
        IptvOverlayRepository.ensureLoaded()
        return IptvSearchSourceSignature.of(XtreamRepository.uiState.value.accounts, IptvOverlayRepository.uiState.value)
    }

    override fun sourceSignatureChanges(): Flow<String?> =
        combine(XtreamRepository.uiState, IptvOverlayRepository.uiState) { state, overlay ->
            IptvSearchSourceSignature.of(state.accounts, overlay)
        }
            .onStart { IptvOverlayRepository.ensureLoaded() }
            .distinctUntilChanged()
}
