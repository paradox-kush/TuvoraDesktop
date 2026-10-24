package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalPlatformWindowInsets
import androidx.compose.ui.platform.PlatformInsets
import androidx.compose.ui.platform.PlatformWindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopHostOs

internal actual val nuvioPlatformExtraTopPadding: Dp = 0.dp
internal actual val nuvioPlatformExtraBottomPadding: Dp = 0.dp
internal actual val nuvioBottomNavigationExtraVerticalPadding: Dp = 0.dp

@Composable
internal actual fun nuvioBottomNavigationBarInsets(): WindowInsets = WindowInsets(0.dp)

@Composable
internal actual fun platformPhysicalTopInset(): Dp = 0.dp

@OptIn(InternalComposeUiApi::class)
@Composable
internal fun ProvideDesktopWindowInsets(
    isFullscreen: Boolean,
    content: @Composable () -> Unit,
) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) {
        content()
        return
    }

    val inheritedInsets = LocalPlatformWindowInsets.current
    // Foundation caches its platform-inset adapter. Keep its identity stable and expose
    // fullscreen/scaling changes as observable values through that same adapter.
    val titleBarTop = rememberUpdatedState(with(LocalDensity.current) { if (isFullscreen) 0 else 28.dp.roundToPx() })
    val windowInsets = remember(inheritedInsets) {
        object : PlatformWindowInsets by inheritedInsets {
            override val captionBar = inheritedInsets.captionBar.withTopInset { titleBarTop.value }
            override val statusBars = inheritedInsets.statusBars.withTopInset { titleBarTop.value }
            override val systemBars = inheritedInsets.systemBars.withTopInset { titleBarTop.value }
        }
    }
    CompositionLocalProvider(LocalPlatformWindowInsets provides windowInsets, content = content)
}

@OptIn(InternalComposeUiApi::class)
private fun PlatformInsets.withTopInset(topInset: () -> Int): PlatformInsets =
    object : PlatformInsets by this {
        override val top: Int
            get() = maxOf(this@withTopInset.top, topInset())
    }

@Composable
internal actual fun ResizeForKeyboard() = Unit
