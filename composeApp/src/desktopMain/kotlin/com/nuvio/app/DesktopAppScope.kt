package com.nuvio.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The application's own coroutine scope: lives as long as the process, cancelled when the window
 * closes ([close], from `Main.kt`). Background work that must not outlive the app (the recording
 * timers) is launched here, so ending the app ends it without anyone remembering to.
 */
internal object DesktopAppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun close() = scope.cancel()
}
