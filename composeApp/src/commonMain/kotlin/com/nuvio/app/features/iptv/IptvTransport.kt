package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpStreamLines

/** Thrown by the default [IptvTransport.readPrefix] to stop a stream once it has enough bytes. */
internal class PrefixComplete : RuntimeException()

/**
 * The two whole-body transports the Xtream and M3U clients use, behind one swappable seam so an
 * integration test can stand up a fake panel (Step 0.3: main server refusing connections, backup
 * answering) without a socket. Production is exactly the platform helpers they always called.
 */
internal interface IptvTransport {
    suspend fun getText(url: String, dnsProvider: String?): String
    suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit)

    /**
     * The first [maxBytes] bytes of [url]'s body as text, then the connection is closed — even when
     * the server ignored the `Range` header in [headers] and is streaming 190 MB. The failover
     * probe for M3U links ("does this server hand out a playlist?").
     */
    suspend fun readPrefix(url: String, userAgent: String?, dnsProvider: String?, headers: Map<String, String>, maxBytes: Int): String {
        val sb = StringBuilder()
        try {
            streamLines(url, userAgent, dnsProvider) { line ->
                sb.append(line).append('\n')
                if (sb.length >= maxBytes) throw PrefixComplete()
            }
        } catch (_: PrefixComplete) {
            // enough bytes
        }
        return sb.toString()
    }

    companion object {
        /** Test seam: replaced by integration tests, restored to [Platform] after. */
        var current: IptvTransport = Platform
    }

    object Platform : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String = httpGetText(url, dnsProvider)
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) =
            httpStreamLines(url, userAgent, dnsProvider, onLine = onLine)

        override suspend fun readPrefix(url: String, userAgent: String?, dnsProvider: String?, headers: Map<String, String>, maxBytes: Int): String {
            val sb = StringBuilder()
            httpStreamLines(url, userAgent, dnsProvider, headers, maxBytes.toLong()) { sb.append(it).append('\n') }
            return sb.toString()
        }
    }
}
