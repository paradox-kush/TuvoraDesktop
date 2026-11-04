package com.nuvio.app.features.player

/**
 * Decides the host-side `controlsVisible` for a desktop native controls layer (B208).
 *
 * The native layer hides itself after its idle timeout and reports `hideChrome`; it asks to come
 * back with `cursorActivity` (mouse moved over the video), `keepChromeVisible` or `toggleChrome`.
 * A host that does not record these keeps pushing an unchanged `controlsVisible = true`, which the
 * engine drops, so the controls never return on mouse movement.
 *
 * Returns the new visibility, or null when [type] does not concern visibility.
 */
internal object NativeControlsVisibilityPolicy {
    fun next(visible: Boolean, type: String): Boolean? = when (type) {
        "hideChrome" -> false
        "cursorActivity", "keepChromeVisible" -> true
        "toggleChrome" -> !visible
        else -> null
    }
}
