package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

// Upstream registers desktop back handlers with Compose's window back dispatcher (NavigationBackHandler), which
// receives Esc and the mouse Back button (MainAppContent feeds it) and orders dialogs before pages. Fork: the same
// registration also lands in a tiny private stack that the AWT-level EscapeKeyBridge below drains, because Compose
// never sees Escape while the native player (or the native party panel window) owns keyboard focus.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
    // The registration is updated in place, so recomposition never reorders which handler is innermost.
    val registration = remember { NativeFocusBackRegistration(enabled, onBack) }
    SideEffect {
        registration.enabled = enabled
        registration.onBack = onBack
    }
    DisposableEffect(registration) {
        NativeFocusBackStack.register(registration)
        EscapeKeyBridge.retain()
        onDispose {
            NativeFocusBackStack.unregister(registration)
            EscapeKeyBridge.release()
        }
    }
}

private class NativeFocusBackRegistration(
    @Volatile var enabled: Boolean,
    @Volatile var onBack: () -> Unit,
)

/** Innermost (most recently registered) enabled handler wins, mirroring Android's nested back handlers. */
private object NativeFocusBackStack {
    private val registrations = mutableListOf<NativeFocusBackRegistration>()

    @Synchronized
    fun register(registration: NativeFocusBackRegistration) {
        registrations.add(registration)
    }

    @Synchronized
    fun unregister(registration: NativeFocusBackRegistration) {
        registrations.remove(registration)
    }

    /** Runs the innermost enabled handler; true when one took the event. */
    fun dispatch(): Boolean {
        val target = synchronized(this) { registrations.lastOrNull { it.enabled } } ?: return false
        target.onBack()
        return true
    }
}

/**
 * Fork: Escape is intercepted at the AWT level, not only by Compose's own Esc input. The native player
 * surface owns keyboard focus while a video plays, so Compose never sees Escape there and the fullscreen
 * player could not be left with the keyboard (Live TV fullscreen trap, 24da89597). The bridge consumes
 * Escape only when a registered handler took it; otherwise the event continues to Compose's own Esc input.
 * The native party panel window (PlayerSidePanelHost) relies on this too: Escape reaches its handler whichever
 * window has focus. Mouse Back goes through NavigationBackHandler (upstream 7559c4a82).
 */
private object EscapeKeyBridge {
    private var holders = 0
    private var dispatcher: KeyEventDispatcher? = null

    fun retain() {
        holders += 1
        if (dispatcher != null) return
        val created = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_ESCAPE) {
                return@KeyEventDispatcher false
            }
            NativeFocusBackStack.dispatch() // true = consumed, so Escape doesn't also reach the focused component
        }
        dispatcher = created
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(created)
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        val current = dispatcher ?: return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(current)
        dispatcher = null
    }
}
