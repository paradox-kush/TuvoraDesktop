package com.nuvio.app.core.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Neutral rec-telemetry port (seam S11, partial). Shared code reports playback progress with raw
 * primitives; the fork adapter derives the rec content-type and forwards to core/rec. Keeps shared
 * code off features/core.rec.
 */
interface RecPlaybackReporter {
    fun onProgress(
        itemId: String,
        contentType: String,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
    )
}

interface RecSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)
}

object RecTrackingAccess {
    private var reporterInstance: RecPlaybackReporter? = null
    private var settingsInstance: RecSettings? = null
    val settings: RecSettings
        get() = settingsInstance ?: error("RecSettings not registered — see FeatureWiring")
    fun registerSettings(settings: RecSettings) { settingsInstance = settings }
    val reporter: RecPlaybackReporter
        get() = reporterInstance ?: error("RecPlaybackReporter not registered — see FeatureWiring")
    fun register(reporter: RecPlaybackReporter) { reporterInstance = reporter }
}
