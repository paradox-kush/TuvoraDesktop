package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpStreamLines

/**
 * The two whole-body transports the Xtream and M3U clients use, behind one swappable seam so an
 * integration test can stand up a fake panel (Step 0.3: main server refusing connections, backup
 * answering) without a socket. Production is exactly the platform helpers they always called.
 */
internal interface IptvTransport {
    suspend fun getText(url: String, dnsProvider: String?): String
    suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit)

    companion object {
        /** Test seam: replaced by integration tests, restored to [Platform] after. */
        var current: IptvTransport = Platform
    }

    object Platform : IptvTransport {
        override suspend fun getText(url: String, dnsProvider: String?): String = httpGetText(url, dnsProvider)
        override suspend fun streamLines(url: String, userAgent: String?, dnsProvider: String?, onLine: (String) -> Unit) =
            httpStreamLines(url, userAgent, dnsProvider, onLine = onLine)
    }
}
