package com.nuvio.app.features.servers

/**
 * Which route a media URL (an artwork request, a playback source) takes. A URL is [Route.Pinned] only when it is https and
 * its `host:port` is a server this device pinned a certificate for (trust-on-first-use): those must go through a client or a
 * relay that verifies the pinned certificate. Everything else - CA-signed servers, add-on hosts, IPTV, plain http, the
 * loopback relay itself - is [Route.Direct] and keeps its normal path and normal validation. Pure; shared with Desktop.
 */
internal object PinnedMediaPolicy {
    sealed interface Route {
        data object Direct : Route
        data class Pinned(val authority: String) : Route
    }

    fun route(url: String?, pinnedFingerprint: (authority: String) -> String?): Route {
        if (url.isNullOrBlank() || !url.startsWith("https://", ignoreCase = true)) return Route.Direct
        val authority = serverAuthority(url) ?: return Route.Direct
        return if (pinnedFingerprint(authority) != null) Route.Pinned(authority) else Route.Direct
    }

    /** Route using this device's pin store. */
    fun route(url: String?): Route = route(url) { ServerTrust.shared.pinnedFingerprint(it) }
}

/**
 * The loopback relay's request rules (pure, unit tested). The relay is not an open proxy: it only ever talks to the ONE
 * authority its session was opened for, only for GET/HEAD, only when the request carries the session's unguessable token
 * as the first path segment and addresses the relay by its own loopback host:port.
 */
internal object RelayRequestPolicy {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_HEADER_LINES = 64

    sealed interface Result {
        data class Ok(val method: String, val path: String, val headers: Map<String, String>) : Result

        /** Answer with [status] and nothing else (no echo of the request). */
        data class Reject(val status: Int) : Result
    }

    private val forwardedRequestHeaders = mapOf(
        "range" to "Range",
        "if-range" to "If-Range",
        "if-modified-since" to "If-Modified-Since",
        "if-none-match" to "If-None-Match",
        "accept" to "Accept",
        "user-agent" to "User-Agent",
    )

    private val passedResponseHeaders = mapOf(
        "content-type" to "Content-Type",
        "content-length" to "Content-Length",
        "content-range" to "Content-Range",
        "accept-ranges" to "Accept-Ranges",
        "etag" to "ETag",
        "last-modified" to "Last-Modified",
        "cache-control" to "Cache-Control",
    )

    fun parse(requestLine: String, headerLines: List<String>, token: String, port: Int): Result {
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return Result.Reject(400)
        val method = parts[0]
        if (method != "GET" && method != "HEAD") return Result.Reject(405)
        val target = parts[1]
        if (!target.startsWith("/") || target.any { it.code < 0x21 || it.code == 0x7f }) return Result.Reject(400)
        val prefix = "/$token/"
        if (!target.startsWith(prefix)) return Result.Reject(404)
        val path = "/" + target.removePrefix(prefix)

        val headers = mutableMapOf<String, String>()
        var host: String? = null
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) return Result.Reject(400)
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when {
                name == "host" -> host = value
                name == "content-length" && value != "0" -> return Result.Reject(400)
                name == "transfer-encoding" -> return Result.Reject(400)
                name in forwardedRequestHeaders -> headers[forwardedRequestHeaders.getValue(name)] = value
            }
        }
        if (host != "127.0.0.1:$port") return Result.Reject(403)
        return Result.Ok(method, path, headers)
    }

    /** The response headers to hand back: a fixed allow-list (never Set-Cookie, never a server's own Location). */
    fun responseHeaders(upstream: Map<String, List<String>>): List<Pair<String, String>> =
        upstream.mapNotNull { (name, values) ->
            passedResponseHeaders[name.lowercase()]?.let { it to values.joinToString(",") }
        }

    /** A redirect is followed only when it stays on the session's authority: returns the path to serve via the relay, else null. */
    fun sameAuthorityLocation(location: String?, authority: String): String? {
        if (location.isNullOrBlank()) return null
        if (location.startsWith("/") && !location.startsWith("//")) return location
        if (!location.startsWith("https://", ignoreCase = true)) return null
        if (serverAuthority(location) != authority.lowercase()) return null
        val rest = location.substringAfter("://").substringAfter('/', "")
        return "/$rest"
    }
}
