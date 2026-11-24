package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app's window is open and visible (not minimised, not hidden), independent of keyboard focus.
 *
 * [AppForegroundMonitor] answers a different question on Desktop: it follows the *active* window, so it reads
 * "background" the moment the user clicks another app. Work that should keep running while the window is still on
 * screen (a live-score banner the user glances at from a second monitor) follows this instead. Fed only by the
 * Desktop host (`TrackAppWindowPresence`); on every other platform it stays at its default and nothing reads it.
 */
internal object AppWindowPresence {
    private val _open = MutableStateFlow(true)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun set(open: Boolean) {
        _open.value = open
    }
}
