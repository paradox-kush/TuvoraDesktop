package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.WindowState
import com.nuvio.app.core.sync.AppWindowPresence
import java.awt.Desktop
import java.awt.desktop.AppHiddenEvent
import java.awt.desktop.AppHiddenListener

/**
 * Publishes "the window is open and visible" to [AppWindowPresence]: true unless the window is minimised or (macOS,
 * Cmd+H) the whole app is hidden. Losing keyboard focus does NOT count: the window is still on screen.
 */
@Composable
internal fun TrackAppWindowPresence(windowState: WindowState) {
    var appHidden by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // macOS "Hide": the window is not minimised but is not on screen either. Other hosts have no such event.
        val listener = object : AppHiddenListener {
            override fun appHidden(e: AppHiddenEvent) { appHidden = true }
            override fun appUnhidden(e: AppHiddenEvent) { appHidden = false }
        }
        val registered = runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_EVENT_HIDDEN)) {
                desktop.addAppEventListener(listener)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        onDispose {
            if (registered) runCatching { Desktop.getDesktop().removeAppEventListener(listener) }
            AppWindowPresence.set(false)
        }
    }
    LaunchedEffect(windowState) {
        snapshotFlow { !windowState.isMinimized && !appHidden }.collect { AppWindowPresence.set(it) }
    }
}
