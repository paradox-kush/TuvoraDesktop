package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Desktop has no system back gesture, so Escape plays that role.
 *
 * Without this the handler was a no-op and anything that relies on back to get out — the Live TV
 * fullscreen dock, player side panels, the poster zoom overlay, the collection editor sheets —
 * had no keyboard escape at all.
 *
 * Handlers form a LIFO stack so the innermost enabled one wins, matching how Android resolves
 * nested [PlatformBackHandler]s: a panel opened over a screen closes before the screen does.
 */
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // A stable identity that reads through to the newest lambda, so recomposition doesn't
        // churn the stack and reorder which handler is innermost.
        val handler: () -> Unit = { latestOnBack() }
        EscapeBackDispatcher.push(handler)
        onDispose { EscapeBackDispatcher.remove(handler) }
    }
}

private object EscapeBackDispatcher {
    /** Last element is the innermost handler. */
    private val handlers = ArrayDeque<() -> Unit>()
    private var dispatcher: KeyEventDispatcher? = null

    fun push(handler: () -> Unit) {
        handlers.addLast(handler)
        install()
    }

    fun remove(handler: () -> Unit) {
        handlers.removeAll { it === handler }
        if (handlers.isEmpty()) uninstall()
    }

    private fun install() {
        if (dispatcher != null) return
        val created = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_ESCAPE) {
                return@KeyEventDispatcher false
            }
            val handler = handlers.lastOrNull() ?: return@KeyEventDispatcher false
            handler()
            true // consumed, so Escape doesn't also reach the focused component
        }
        dispatcher = created
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(created)
    }

    private fun uninstall() {
        val current = dispatcher ?: return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(current)
        dispatcher = null
    }
}
