package com.nuvio.app

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.contracts.MemoryPortAccess
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.features.common.lifecycle.FeatureRegistry
import com.nuvio.app.navigation.AppRoute
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

private val nuvioBackgroundColor = UIColor(red = 0.051, green = 0.051, blue = 0.051, alpha = 1.0)

@Suppress("unused")
fun MainViewController(): UIViewController = nuvioComposeViewController {
    App()
}

@Suppress("unused")
fun MainViewController(
    initialTabName: String,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
    onAppReady: (Boolean) -> Unit,
    onTabTitles: (String, String, String, String, String, String, String, String) -> Unit,
    nativeProfileSwitcherController: NativeProfileSwitcherController,
): UIViewController {
    val initialTab = AppScreenTab.fromName(initialTabName)
    return nuvioComposeViewController {
        App(
            initialTab = initialTab,
            useNativeNavigation = true,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = initialTab == AppScreenTab.Home,
            bypassAppGate = initialTab != AppScreenTab.Home,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = { tab -> onActivate(tab.name) },
            onAppReady = onAppReady,
            onTabTitles = onTabTitles,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
        )
    }
}

@Suppress("unused")
fun ScreenViewController(
    route: AppRoute,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
): UIViewController = nuvioComposeViewController {
    App(
        initialRoute = route,
        useNativeNavigation = true,
        ownsAppRuntime = false,
        bypassAppGate = true,
        onNavigate = onNavigate,
        onGoBack = onGoBack,
        onReplace = onReplace,
        onActivate = { tab -> onActivate(tab.name) },
    )
}

private fun nuvioComposeViewController(
    content: @androidx.compose.runtime.Composable () -> Unit,
): UIViewController {
    ensureIosRuntimeBootstrapped()
    return ComposeUIViewController(
        configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
        content = content,
    ).apply {
        view.backgroundColor = nuvioBackgroundColor
    }
}

/**
 * iOS process bootstrap — the Kotlin analog of NuvioApplication.onCreate. Swift's iOSApp.init
 * wires only analytics and the memory-pressure source, so registerFeatureContributions() was
 * never called on iOS and every feature port sat unregistered (IptvCatalog.current would throw).
 * Runs at the single ComposeUIViewController chokepoint, before any composition reads a port.
 */
private fun ensureIosRuntimeBootstrapped() {
    if (!FeatureRegistry.isInitialized) {
        registerFeatureContributions()
    }
    // Static half of the iOS memory probe (ProcessInfo.physicalMemory); the dynamic pressure
    // half is DispatchSource in iOSApp.swift feeding AppMemory.onPressure/onRelax.
    MemoryPortAccess.current().setBaseTier(
        MemoryTierPolicy.iosTier(NSProcessInfo.processInfo.physicalMemory.toLong()),
    )
}
