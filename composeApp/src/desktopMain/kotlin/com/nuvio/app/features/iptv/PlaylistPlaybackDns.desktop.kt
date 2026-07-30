package com.nuvio.app.features.iptv

/**
 * Desktop: no per-playlist DoH hook wired yet (same posture as iOS) — the url is returned
 * unchanged and mpv resolves via the system resolver. Port the Android DoH rewrite if needed.
 */
actual suspend fun resolveLivePlaybackUrl(url: String, dnsProvider: String?): LivePlaybackResolution =
    LivePlaybackResolution(url)
