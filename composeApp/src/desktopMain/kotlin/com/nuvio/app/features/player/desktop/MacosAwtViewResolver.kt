package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.awt.Container
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

internal data class NativePlayerHostTarget(
    val viewPtr: Long,
    /** Player rectangle in top-left-origin coordinates relative to [viewPtr]. */
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** macOS hosts the player in a bounded child of the window view; Windows uses the Canvas HWND. */
    val needsBoundedChild: Boolean = false,
)

internal object AwtNativeViewResolver {
    fun resolveNativePlayerHost(component: Component): NativePlayerHostTarget =
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> MacosAwtViewResolver.resolveNativePlayerHost(component)
            DesktopHostOs.WINDOWS -> NativePlayerHostTarget(
                viewPtr = WindowsAwtViewResolver.resolveNativeViewPointer(component),
            )
            else -> error("Native desktop playback is not implemented for ${DesktopHostOs.current}.")
        }

    /** Top-level native window operations do not need a player-bounded child. */
    fun resolveNativeViewPointer(component: Component): Long = when (DesktopHostOs.current) {
        DesktopHostOs.MACOS -> MacosAwtViewResolver.resolveWindowViewPointer(component)
        DesktopHostOs.WINDOWS -> WindowsAwtViewResolver.resolveNativeViewPointer(component)
        else -> error("Native desktop views are not implemented for ${DesktopHostOs.current}.")
    }
}

private object MacosAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativePlayerHost(component: Component): NativePlayerHostTarget {
        val root = component.windowContentRoot()
        val location = SwingUtilities.convertPoint(component, 0, 0, root)
        return NativePlayerHostTarget(
            viewPtr = resolveWindowViewPointer(component),
            x = location.x,
            y = location.y,
            width = component.width,
            height = component.height,
            needsBoundedChild = true,
        )
    }

    fun resolveWindowViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val platformWindow = invokeObject(peer, "getPlatformWindow")
        val contentView = invokeObject(platformWindow, "getContentView")
        val pointer = invokeLong(contentView, "getAWTView")
        if (pointer == 0L) {
            error("macOS AWT view pointer was zero.")
        }
        return pointer
    }

    private fun Component.windowContentRoot(): Container {
        val window = SwingUtilities.getWindowAncestor(this)
            ?: error("AWT component is not attached to a window.")
        return (window as? RootPaneContainer)?.contentPane
            ?: error("AWT player window has no Swing content pane.")
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeObject(target: Any, methodName: String): Any =
        findMethod(target.javaClass, methodName).invoke(target)
            ?: error("$methodName returned null.")

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

private object WindowsAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val pointer = invokeLong(peer, "getHWnd")
        if (pointer == 0L) {
            error("Windows AWT HWND pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}
