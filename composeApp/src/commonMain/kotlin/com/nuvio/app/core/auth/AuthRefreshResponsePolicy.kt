package com.nuvio.app.core.auth

/**
 * Decides how the HTTP layer should treat a response to Supabase's token-refresh call.
 *
 * supabase-kt retries a refresh only when the status is one of its NETWORK_ERROR_CODES
 * (500, 502, 503, 504, 520-524, 530) or the request never reached the server. Every other failing
 * status falls through to `clearSession()`, which deletes the persisted session outright — the next
 * launch then reports "No entry with the key sb-<ref>-session" and the user is signed out. A
 * rate-limit (429), a proxy timeout (408) or a Cloudflare edge block (403 carrying a cf-ray) is
 * therefore enough to destroy a perfectly valid session.
 *
 * Retrying those at the HTTP layer means the library never sees them.
 *
 * Retries MUST stay inside GoTrue's refresh-token reuse interval. Re-presenting an already-rotated
 * token inside that window returns the same child token, but presenting an old token after it trips
 * reuse detection and revokes the whole family — a late retry is worse than none. Keep the attempt
 * count and the delay small.
 */
internal fun shouldRetryAuthRefreshResponse(
    statusCode: Int,
    path: String,
    grantType: String?,
    server: String?,
    cloudflareRay: String?
): Boolean {
    if (!path.endsWith("/auth/v1/token") || grantType != "refresh_token") return false
    if (statusCode == 408 || statusCode == 429) return true
    return statusCode == 403 && (
        !cloudflareRay.isNullOrBlank() ||
            server?.contains("cloudflare", ignoreCase = true) == true
        )
}

/**
 * True only when the refresh endpoint actually said the session is gone.
 *
 * Status alone is not enough: an edge proxy can answer 403 without GoTrue ever being reached, and
 * treating that as a dead session signs the user out for someone else's outage.
 */
internal fun isInvalidAuthRefreshResponse(statusCode: Int, responseBody: String): Boolean {
    if (statusCode !in setOf(400, 401, 403)) return false
    val normalizedBody = responseBody.lowercase()
    return INVALID_AUTH_SESSION_MARKERS.any(normalizedBody::contains)
}

private val INVALID_AUTH_SESSION_MARKERS = listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "invalid_grant",
    "session not found",
    "session_not_found",
    "invalid session"
)
