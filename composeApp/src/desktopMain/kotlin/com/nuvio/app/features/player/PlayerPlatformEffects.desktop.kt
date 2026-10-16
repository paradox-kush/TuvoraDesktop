package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun FullscreenPlayerDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}

@Composable
actual fun HidePlayerSystemBars() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    val keepAwakeController = remember { DesktopKeepAwakeController() }

    SideEffect {
        keepAwakeController.setEnabled(keepScreenAwake)
    }

    DisposableEffect(keepAwakeController) {
        onDispose {
            keepAwakeController.close()
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) {
    SideEffect {
        DesktopPlayerPictureInPicture.update(isPlaying = isPlaying, videoSize = videoSize)
    }

    DisposableEffect(Unit) {
        onDispose {
            DesktopPlayerPictureInPicture.clear()
        }
    }
}

@Composable
actual fun rememberIsInPictureInPicture(): Boolean {
    val version by DesktopPlayerPictureInPicture.changes.collectAsState()
    return version >= 0 && DesktopPlayerPictureInPicture.isEnabled
}

actual fun togglePlayerPictureInPicture() {
    DesktopPlayerPictureInPicture.toggle()
}

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? = null

private class DesktopKeepAwakeController : AutoCloseable {
    private var caffeinateProcess: Process? = null
    private var windowsDisplaySleepInhibited = false
    private var linuxInhibitCookie: Long? = null
    private var linuxInhibitProcess: Process? = null
    private var linuxInhibitExecutor: ExecutorService? = null

    fun setEnabled(enabled: Boolean) {
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> {
                if (enabled) {
                    startCaffeinate()
                } else {
                    stopCaffeinate()
                }
            }

            DesktopHostOs.WINDOWS -> setWindowsDisplaySleepInhibited(enabled)
            DesktopHostOs.LINUX -> setLinuxInhibitEnabled(enabled)
            DesktopHostOs.UNKNOWN -> Unit
        }
    }

    private fun startCaffeinate() {
        if (caffeinateProcess?.isAlive == true) return

        val currentPid = ProcessHandle.current().pid().toString()
        caffeinateProcess = runCatching {
            ProcessBuilder(
                "/usr/bin/caffeinate",
                "-d",
                "-i",
                "-w",
                currentPid,
            ).start()
        }.getOrNull()
    }

    private fun stopCaffeinate() {
        caffeinateProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        caffeinateProcess = null
    }

    private fun setWindowsDisplaySleepInhibited(inhibited: Boolean) {
        if (windowsDisplaySleepInhibited == inhibited) return

        val applied = runCatching {
            NativePlayerBridge.setWindowsDisplaySleepInhibited(inhibited)
        }.getOrDefault(false)
        if (applied) {
            windowsDisplaySleepInhibited = inhibited
        }
    }

    // Subprocess calls below block, so route them off the Compose thread.
    private fun linuxExecutor(): ExecutorService =
        linuxInhibitExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-linux-screensaver-inhibit").apply { isDaemon = true }
        }.also { linuxInhibitExecutor = it }

    // Held together, not as an either/or fallback: testing on KDE Plasma showed a successful
    // D-Bus Inhibit call alone doesn't stop the screen lock, only systemd-inhibit does - other
    // DEs may lean on the D-Bus call instead, so both run.
    private fun setLinuxInhibitEnabled(enabled: Boolean) {
        linuxExecutor().execute {
            if (enabled) {
                if (linuxInhibitCookie == null) tryStartDbusScreenSaverInhibit()
                if (linuxInhibitProcess?.isAlive != true) tryStartSystemdInhibit()
            } else {
                stopDbusScreenSaverInhibit()
                stopSystemdInhibit()
            }
        }
    }

    // Same freedesktop ScreenSaver D-Bus call mpv/VLC/browsers use - KDE, GNOME, and XFCE all honor it.
    private fun tryStartDbusScreenSaverInhibit(): Boolean {
        linuxInhibitCookie = runCatching {
            val (exitCode, output) = runDbusSendBlocking(
                "--print-reply=literal",
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.Inhibit",
                "string:Nuvio",
                "string:Media playback",
            )
            // --print-reply=literal still prefixes the value with its D-Bus type name (e.g.
            // "uint32 19346"), it isn't a bare number - take the last token.
            output.trim().substringAfterLast(' ').takeIf { exitCode == 0 && it.isNotEmpty() }?.toLongOrNull()
        }.getOrNull()
        return linuxInhibitCookie != null
    }

    private fun stopDbusScreenSaverInhibit() {
        val cookie = linuxInhibitCookie ?: return
        runCatching {
            runDbusSendBlocking(
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.UnInhibit",
                "uint32:$cookie",
            )
        }
        linuxInhibitCookie = null
    }

    // If dbus-daemon never replies, readText() below blocks forever before a waitFor() timeout
    // would even get a chance to fire, so the watchdog thread is the thing that actually kills it.
    private fun runDbusSendBlocking(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("dbus-send", "--session", "--type=method_call", *args)
            .redirectErrorStream(true)
            .start()
        Thread {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }.apply {
            isDaemon = true
            start()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return exitCode to output
    }

    // Holds a systemd-logind idle/sleep lock for as long as this child process stays alive.
    private fun tryStartSystemdInhibit(): Boolean {
        linuxInhibitProcess = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=Nuvio",
                "--why=Media playback",
                "--mode=block",
                "sleep",
                "infinity",
            ).start()
        }.getOrNull()
        return linuxInhibitProcess?.isAlive == true
    }

    private fun stopSystemdInhibit() {
        linuxInhibitProcess
            ?.takeIf(Process::isAlive)
            ?.destroy()
        linuxInhibitProcess = null
    }

    override fun close() {
        stopCaffeinate()
        setWindowsDisplaySleepInhibited(false)
        linuxInhibitExecutor?.let { executor ->
            executor.execute {
                stopDbusScreenSaverInhibit()
                stopSystemdInhibit()
            }
            executor.shutdown()
        }
    }
}

/**
 * The native video and its controls cover every Compose pixel inside the player, so a side panel drawn in the
 * player's Box is invisible and unclickable on desktop (tuvora-desktop-player-controls). The panel gets its own
 * owned, borderless, modeless window pinned over the owner's right edge instead: it stacks above the native layer,
 * follows the owner when it moves or resizes, and goes when the panel closes or the player leaves composition.
 * Escape reaches the panel's PlatformBackHandler through the AWT-level EscapeKeyBridge, whichever window has focus.
 */
@Composable
internal actual fun PlayerSidePanelHost(
    visible: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    val owner = remember { playerOwnerWindow() }
    if (owner == null) {
        content()
        return
    }
    val panelWidth = width.value.toInt()
    val listener = remember { arrayOfNulls<java.awt.event.ComponentListener>(1) }
    androidx.compose.ui.window.DialogWindow(
        visible = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        create = {
            androidx.compose.ui.awt.ComposeDialog(owner, java.awt.Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isResizable = false
                defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                val translucent = runCatching {
                    owner.graphicsConfiguration.device.isWindowTranslucencySupported(
                        java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT,
                    )
                }.getOrDefault(false)
                if (translucent) isTransparent = true else background = java.awt.Color.BLACK
                val dialog = this
                fun follow() {
                    if (!owner.isShowing) return
                    val root: java.awt.Component = (owner as? javax.swing.RootPaneContainer)?.rootPane ?: owner
                    val origin = root.locationOnScreen
                    dialog.bounds = com.nuvio.app.features.player.desktop.PlayerSidePanelBounds.of(
                        origin.x, origin.y, root.width, root.height, panelWidth,
                    )
                }
                follow()
                val follower = object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) = follow()
                    override fun componentMoved(e: java.awt.event.ComponentEvent?) = follow()
                }
                owner.addComponentListener(follower)
                listener[0] = follower
            }
        },
        dispose = { dialog ->
            listener[0]?.let(owner::removeComponentListener)
            listener[0] = null
            dialog.dispose()
        },
    ) {
        content()
    }
}

/** The app's main Compose window (the PiP window is a plain JDialog, never a ComposeWindow). */
private fun playerOwnerWindow(): java.awt.Window? =
    (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? androidx.compose.ui.awt.ComposeWindow)
        ?: java.awt.Window.getWindows().firstOrNull { it is androidx.compose.ui.awt.ComposeWindow && it.isShowing }
