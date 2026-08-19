package com.nuvio.app.core.rec

import com.nuvio.app.core.contracts.RecPlaybackReporter
import com.nuvio.app.core.contracts.RecSettings

internal object RecPlaybackReporterImpl : RecPlaybackReporter {
    override fun startLogging() {
        RecEventLogger.start()
    }

    override fun onProgress(
        itemId: String,
        contentType: String,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
    ) {
        RecPlaybackTracker.onProgress(
            itemId = itemId,
            contentType = recContentTypeOf(contentType = contentType, season = season, episode = episode),
            season = season,
            episode = episode,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }
}

internal object RecSettingsImpl : RecSettings {
    override val enabled = RecEventSettings.enabled
    override fun setEnabled(value: Boolean) = RecEventSettings.setEnabled(value)
}
