package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
) {
    // Tuvora pins the brand wordmark to a single Tuvora asset across every theme/icon. Upstream
    // selects a per-theme Nuvio wordmark here; the fork renders only the Tuvora wordmark so no Nuvio
    // branding ever appears (the cosmetic theme/icon system still works everywhere else). This stays
    // the one brand chokepoint — every caller (login, splash, member badge) shows Tuvora.
    Image(
        painter = painterResource(Res.drawable.app_logo_wordmark),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
