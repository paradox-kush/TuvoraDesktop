package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.PlaybackGate

/** Forwards the shared PlaybackGate signal to the fork IptvPlaybackGate. Registered by FeatureWiring. */
internal object IptvPlaybackGateAdapter : PlaybackGate {
    override fun setPlaybackActive(active: Boolean) {
        IptvPlaybackGate.setPlaybackActive(active)
    }
}
