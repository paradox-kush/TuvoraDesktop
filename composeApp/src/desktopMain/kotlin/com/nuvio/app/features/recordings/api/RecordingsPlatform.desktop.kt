package com.nuvio.app.features.recordings.api

/** No exact-alarm permission on this target: nothing to prompt for. */
actual object RecordingsPlatform {
    actual fun takeExactAlarmPromptTurn(): Boolean = false
    actual fun openExactAlarmSettings(): Boolean = false
}
