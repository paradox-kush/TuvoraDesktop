package com.nuvio.app.core.contracts

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.nuvio.app.features.settings.SettingsPage

/** Opaque handle to the IPTV settings state, collected fork-side; shared code only passes it back. */
internal interface IptvSettingsState

/**
 * Spatial contract (Invariant S): the IPTV pages of the settings screen.
 *
 * SettingsScreen is upstream-shared and must not import the Xtream subsystem. It asks this section to
 * (composably) collect its own state and to render the IPTV pages into the settings LazyColumn. The
 * section owns everything IPTV — which pages it answers to, its state, its navigation side effects.
 * No-op default (null section, see [IptvSettingsSectionAccess]): the IPTV pages simply do not render,
 * which is the correct behaviour when the IPTV feature is absent (and in unit tests/previews).
 */
internal interface IptvSettingsSection {
    /** Composably collect the section's state; the returned handle is opaque to shared code. */
    @Composable
    fun rememberState(): IptvSettingsState

    /**
     * Render [page] into the settings list if it is one of the IPTV pages. Returns true when it
     * handled [page]; false to let the caller fall through. [state] is the handle from [rememberState];
     * [isTablet] selects the phone vs tablet row styling; [onPageChange] performs settings navigation.
     */
    fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        state: IptvSettingsState,
        onPageChange: (SettingsPage) -> Unit,
    ): Boolean

    /** Header-title override for the reused Add/Edit playlist page, or null to use the static title res. */
    @Composable
    fun headerTitleOrNull(page: SettingsPage): String?
}

internal object IptvSettingsSectionAccess {
    private var section: IptvSettingsSection? = null

    fun register(s: IptvSettingsSection) {
        section = s
    }

    /** Null until the IPTV feature registers — the no-op default. */
    fun current(): IptvSettingsSection? = section

    fun resetForTest() {
        section = null
    }
}
