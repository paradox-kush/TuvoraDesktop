package com.nuvio.app.features.recordings.api

/**
 * The few OS questions the recordings screens ask. Desktop has no exact-alarm permission to ask for:
 * both answers are false.
 */
expect object RecordingsPlatform {
    /** True once per install where an OS permission could be offered. False where there is none. */
    fun takeExactAlarmPromptTurn(): Boolean

    /** Opens the system screen that allows exact alarms. False when this platform has none. */
    fun openExactAlarmSettings(): Boolean
}
