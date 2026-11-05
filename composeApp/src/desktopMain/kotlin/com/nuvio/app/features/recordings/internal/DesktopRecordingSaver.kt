package com.nuvio.app.features.recordings.internal

import com.nuvio.app.features.recordings.api.RecordingSaver
import com.nuvio.app.features.recordings.api.SaveOutcome
import com.nuvio.app.features.recordings.api.SaveRequest
import java.net.http.HttpClient

/** STUB (red commit): behaviour arrives in a later commit. */
internal class DesktopRecordingSaver(
    private val isOnline: () -> Boolean = { true },
    private val client: HttpClient? = null,
    private val stallMs: Long = RecordingStallPolicy.STALL_MS,
    private val watchdogTickMs: Long = 5_000L,
) : RecordingSaver {
    override suspend fun save(req: SaveRequest, progress: (writtenBytes: Long) -> Unit): SaveOutcome = TODO()
}
