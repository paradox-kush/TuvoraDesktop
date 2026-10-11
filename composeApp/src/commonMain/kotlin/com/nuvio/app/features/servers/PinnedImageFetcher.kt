package com.nuvio.app.features.servers

import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.Fetcher
import coil3.network.NetworkFetcher
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.Options
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Artwork for a server this device pinned a certificate for. Registered BEFORE the normal network fetcher: for a pinned
 * `host:port` it fetches through the pinned client (the certificate is verified against the pin); for every other URL it
 * returns null and Coil falls through to the normal fetcher with normal validation.
 */
internal class PinnedServerImageFetcherFactory(
    private val http: ServerHttp = TrustedServerHttp.shared,
    private val pinnedFingerprint: (String) -> String? = { ServerTrust.shared.pinnedFingerprint(it) },
) : Fetcher.Factory<Uri> {
    private val lock = SynchronizedObject()
    private val delegates = mutableMapOf<String, NetworkFetcher.Factory>()

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val url = data.toString()
        val route = PinnedMediaPolicy.route(url, pinnedFingerprint) as? PinnedMediaPolicy.Route.Pinned ?: return null
        val delegate = synchronized(lock) {
            delegates.getOrPut(route.authority) {
                KtorNetworkFetcherFactory(httpClient = { http.clientFor("https://${route.authority}/") })
            }
        }
        return delegate.create(data, options, imageLoader)
    }
}
