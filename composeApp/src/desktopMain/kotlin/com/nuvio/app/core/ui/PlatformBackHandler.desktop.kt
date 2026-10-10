package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // Upstream's registry (innermost enabled handler wins; the root shell also feeds it from the
    // mouse Back button). The registration is updated in place, so recomposition never reorders
    // which handler is innermost.
    val registration = remember { PlatformBackRegistration(enabled, onBack) }
    SideEffect {
        registration.enabled = enabled
        registration.onBack = onBack
    }
    DisposableEffect(registration) {
        PlatformBackDispatcher.register(registration)
        EscapeKeyBridge.retain()
        onDispose {
            PlatformBackDispatcher.unregister(registration)
            EscapeKeyBridge.release()
        }
    }
}

/**
 * Fork: Escape is intercepted at the AWT level, not only by the Compose root's key handler. The
 * native player surface owns keyboard focus while a video plays, so a Compose-level handler never
 * sees Escape there and the fullscreen player could not be left with the keyboard (Live TV
 * fullscreen trap, 24da89597). The bridge consumes Escape only when a registered handler took it;
 * otherwise the event continues to the Compose root, which pops the back stack.
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
            PlatformBackDispatcher.dispatch() // true = consumed, so Escape doesn't also reach the focused component
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
