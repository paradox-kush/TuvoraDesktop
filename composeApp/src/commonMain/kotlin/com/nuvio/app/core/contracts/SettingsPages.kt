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
     * [isTablet] selects the phone vs tablet row styling; [onPageChange] opens a page (forward);
     * [onNavigateBack] leaves the current page for its parent — a pop on a real nav stack (B103: a
     * finished form must go back, never push its parent on top of itself).
     */
    fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        state: IptvSettingsState,
        onPageChange: (SettingsPage) -> Unit,
        onNavigateBack: () -> Unit,
    ): Boolean

    /** Header-title override for the reused Add/Edit playlist page, or null to use the static title res. */
    @Composable
    fun headerTitleOrNull(page: SettingsPage): String?

    /**
     * The same override, resolved composably but READ AT NAVIGATION TIME (UX19): the page's add/edit
     * mode is chosen in the click handler right before navigating, after composition captured the
     * static titles — so the navigator must ask this resolver, not a pre-built title map.
     */
    @Composable
    fun rememberNavigationTitleOverride(): (SettingsPage) -> String?
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


/**
 * Spatial contract: the media-server pages of the settings screen (the server list, add/sign-in, one server's
 * details, "approve a code"). Same shape and reason as [IptvSettingsSection]: SettingsScreen is shared and must
 * not name the fork's media-server feature, so the feature registers an implementation from the composition
 * root and this screen only forwards the page into the settings list. No registration = the pages (and the
 * Integrations row that opens them) simply do not exist.
 */
internal interface MediaServerSettingsSection {
    /**
     * Renders [page] into the settings list when it is one of the media-server pages (true), false to fall
     * through. [onPageChange] opens a page (forward); [onNavigateBack] pops to the parent (a finished form must
     * go back, never push its parent on top of itself - B103).
     */
    fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        onPageChange: (SettingsPage) -> Unit,
        onNavigateBack: () -> Unit,
    ): Boolean

    /** Header-title override (the details page is titled with the server's own name), or null for the static title res. */
    @Composable
    fun headerTitleOrNull(page: SettingsPage): String?

    /** The same override, resolved composably but READ AT NAVIGATION TIME (the target server is chosen in the click handler). */
    @Composable
    fun rememberNavigationTitleOverride(): (SettingsPage) -> String?
}

internal object MediaServerSettingsSectionAccess {
    private var section: MediaServerSettingsSection? = null

    fun register(s: MediaServerSettingsSection) {
        section = s
    }

    /** Null until the media-server feature registers. */
    fun current(): MediaServerSettingsSection? = section

    fun resetForTest() {
        section = null
    }
}
