package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.beans.PropertyChangeListener

internal actual object AppForegroundMonitor {
    actual fun events(): Flow<AppVisibility> = callbackFlow {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event ->
            // Mirror the Android actual (ON_START/ON_STOP): the app gaining an active window is
            // foreground; losing it (activeWindow -> null) is background.
            when {
                event.newValue is Window -> trySend(AppVisibility.Foreground)
                event.oldValue is Window && event.newValue == null -> trySend(AppVisibility.Background)
            }
        }

        trySend(
            if (focusManager.activeWindow != null) AppVisibility.Foreground else AppVisibility.Background,
        )
        focusManager.addPropertyChangeListener("activeWindow", listener)
        awaitClose {
            focusManager.removePropertyChangeListener("activeWindow", listener)
        }
    }.distinctUntilChanged()
}
