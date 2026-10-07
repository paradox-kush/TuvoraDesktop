package com.nuvio.app.features.mediaserver.api

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.contracts.MediaServerSettingsSection
import com.nuvio.app.features.mediaserver.internal.MediaServerRuntime
import com.nuvio.app.features.mediaserver.internal.ui.mediaServerAddContent
import com.nuvio.app.features.mediaserver.internal.ui.mediaServerApproveContent
import com.nuvio.app.features.mediaserver.internal.ui.mediaServerDetailsContent
import com.nuvio.app.features.mediaserver.internal.ui.mediaServerListContent
import com.nuvio.app.features.settings.SettingsPage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ms_sign_in_to_title
import org.jetbrains.compose.resources.stringResource

/**
 * Which server the details / sign-in pages are about - set right before navigating (a plain var like the IPTV
 * pages' targets; a page that finds it lost after a process restart bounces back).
 */
object MediaServerPages {
    var detailsKey: String? = null
        private set

    /** The entry being signed in on this device (arrived from another device, or its session ended); null = adding a new server. */
    var signInKey: String? = null
        private set

    fun openDetails(key: String) {
        detailsKey = key
    }

    fun openAdd() {
        signInKey = null
    }

    fun openSignIn(key: String) {
        signInKey = key
    }
}

/**
 * The media-server pages of the settings screen (design 5.4): the list, add + sign in, one server, approve a
 * code. Registered from the composition root; shared settings code reaches it only through the neutral contract.
 */
internal object MediaServerSettingsSectionImpl : MediaServerSettingsSection {
    @Composable
    override fun headerTitleOrNull(page: SettingsPage): String? = rememberNavigationTitleOverride()(page)

    @Composable
    override fun rememberNavigationTitleOverride(): (SettingsPage) -> String? {
        val signInTitle = stringResource(Res.string.ms_sign_in_to_title, "%s")
        return { page ->
            val store = MediaServerRuntime.production.entryStore
            when (page) {
                SettingsPage.MediaServerDetails -> MediaServerPages.detailsKey?.let(store::entryByKey)?.name
                SettingsPage.MediaServerAdd -> MediaServerPages.signInKey?.let(store::entryByKey)?.let { signInTitle.replace("%s", it.name) }
                else -> null
            }
        }
    }

    override fun LazyListScope.renderPage(
        page: SettingsPage,
        isTablet: Boolean,
        onPageChange: (SettingsPage) -> Unit,
        onNavigateBack: () -> Unit,
    ): Boolean {
        when (page) {
            SettingsPage.MediaServers -> mediaServerListContent(
                isTablet = isTablet,
                onAdd = {
                    MediaServerPages.openAdd()
                    onPageChange(SettingsPage.MediaServerAdd)
                },
                onApprove = { onPageChange(SettingsPage.MediaServerApprove) },
                onOpen = { entry ->
                    MediaServerPages.openDetails(entry.key)
                    onPageChange(SettingsPage.MediaServerDetails)
                },
            )
            SettingsPage.MediaServerAdd -> mediaServerAddContent(isTablet = isTablet, onDone = onNavigateBack)
            SettingsPage.MediaServerDetails -> if (MediaServerPages.detailsKey == null) {
                // Process-death restore: the target server is a plain var - leave the page.
                item { LaunchedEffect(Unit) { onNavigateBack() } }
            } else {
                mediaServerDetailsContent(
                    isTablet = isTablet,
                    onSignIn = { entry ->
                        MediaServerPages.openSignIn(entry.key)
                        onPageChange(SettingsPage.MediaServerAdd)
                    },
                    onLeave = onNavigateBack,
                )
            }
            SettingsPage.MediaServerApprove -> mediaServerApproveContent(isTablet = isTablet)
            else -> return false
        }
        return true
    }
}
