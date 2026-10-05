package com.nuvio.app.features.library

/**
 * B03/D5 — whether a library pull also runs the Tuvora (Nuvio-sync) delta. Always: the visible
 * Movies/Series library may be sourced from Trakt/Simkl, but IPTV live favourites are always stored in
 * the local synced library ([LibraryRepository.localItems]), so a device with a tracking provider must
 * still receive the favourites other devices made. Twin: NuvioTV `LiveFavoriteStoragePolicy`.
 */
object LibraryPullPolicy {
    fun pullsNuvioLibrary(trackingProviderActive: Boolean): Boolean = true
}
