package com.nuvio.app

import com.nuvio.app.core.ui.NativeNavigationTab

enum class AppScreenTab {
    Home,
    Search,
    Library,
    Iptv,
    Sports,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

// Nullable for tabs a native tab bar might not carry; the fork's iOS bar carries all six.
internal fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab? = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.Iptv -> NativeNavigationTab.Iptv
    AppScreenTab.Sports -> NativeNavigationTab.Sports
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

internal fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.Iptv -> AppScreenTab.Iptv
    NativeNavigationTab.Sports -> AppScreenTab.Sports
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}
