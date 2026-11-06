package com.nuvio.app.features.iptv

/**
 * Desktop: no per-playlist DoH hook wired yet (same posture as iOS) — the url is returned
 * unchanged (so no redirect probe GET ever spends a single-use link) and mpv resolves via the system resolver. Port the Android DoH rewrite if needed.
 */
actual suspend fun resolveLivePlaybackUrl(url: String, dnsProvider: String?, followRedirects: Boolean): LivePlaybackResolution =
    LivePlaybackResolution(url)
