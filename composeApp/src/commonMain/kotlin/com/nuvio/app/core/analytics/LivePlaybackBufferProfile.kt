package com.nuvio.app.core.analytics

import kotlin.concurrent.Volatile

/**
 * The buffer thresholds the active player engine is really using.
 *
 * Published by whichever platform engine builds the player, because the knobs are engine
 * specific (Media3's `LoadControl` on Android, mpv's cache options elsewhere) and there is no
 * shared type for them. [LivePlaybackFreezeReporter] reads it when a freeze is reported: a
 * stall that never clears is only explained by these numbers if the player needed more
 * buffered media to resume than a realtime source could produce.
 *
 * Null means the engine does not express its buffering this way, and the freeze event simply
 * omits those properties rather than reporting numbers that were never in effect.
 */
object LivePlaybackBufferProfile {

    data class Values(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
    )

    @Volatile
    var current: Values? = null
}
