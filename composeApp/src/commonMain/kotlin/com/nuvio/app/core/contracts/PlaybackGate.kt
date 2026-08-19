package com.nuvio.app.core.contracts

/**
 * Firewall port for the IPTV playback gate: while a live/IPTV stream is playing, background work
 * (playlist auto-refresh, app-chrome banners) must stand down. App.kt signals the boundary; the fork
 * gate consumes it. No-op default so a build without IPTV simply never gates.
 */
interface PlaybackGate {
    fun setPlaybackActive(active: Boolean)
}

object PlaybackGateAccess {
    private val noOp = object : PlaybackGate {
        override fun setPlaybackActive(active: Boolean) {}
    }
    private var gate: PlaybackGate? = null

    fun register(g: PlaybackGate) {
        gate = g
    }

    fun current(): PlaybackGate = gate ?: noOp

    fun resetForTest() {
        gate = null
    }
}
