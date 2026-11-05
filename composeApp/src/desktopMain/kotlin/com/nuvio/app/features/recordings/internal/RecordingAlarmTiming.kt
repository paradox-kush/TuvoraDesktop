package com.nuvio.app.features.recordings.internal

/** STUB (red commit): behaviour arrives in the next commit. */
internal object RecordingAlarmTiming {
    const val MAX_CHUNK_MS = 60_000L

    sealed interface Step {
        data object FireNow : Step
        data class Sleep(val ms: Long) : Step
    }

    fun next(nowMs: Long, fireAtMs: Long, maxChunkMs: Long = MAX_CHUNK_MS): Step = Step.FireNow
}
