package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastPlacement
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.iptv.PinSuggestionPolicy.Candidate
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_pin_suggest_not_now
import nuvio.composeapp.generated.resources.iptv_pin_suggest_pin
import nuvio.composeapp.generated.resources.iptv_pin_suggest_pin_all
import nuvio.composeapp.generated.resources.iptv_pin_suggest_pin_cd
import nuvio.composeapp.generated.resources.iptv_pin_suggest_pinned_all_toast
import nuvio.composeapp.generated.resources.iptv_pin_suggest_pinned_one_toast
import nuvio.composeapp.generated.resources.iptv_pin_suggest_title
import nuvio.composeapp.generated.resources.iptv_pin_suggest_turn_off
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * "You keep coming back to these N": the Live tab's pin suggestion. A rail of channel tiles, each with a
 * Pin chip, and one slim line under it — Pin all N, Not now (quiet for 14 days), and Turn off in Settings.
 * What is offered is decided by [PinSuggestionPolicy]; this only draws it and calls [LivePinSuggestions].
 * Pinning = making the channel a live favourite, which the hub lists first (see [LiveFavouritePort]).
 */
@Composable
internal fun LivePinSuggestionRail(
    candidates: List<Candidate>,
    epg: Map<String, ChannelEpg>,
    sectionPadding: Dp,
    onPlay: (MetaPreview) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val actions = LivePinSuggestions.actions
    val scope = rememberCoroutineScope()
    val tileWidth = landscapePosterWidth(rememberPosterCardStyleUiState().widthDp)
    val guideDataGeneration by XtreamHubRepository.guideDataGeneration.collectAsStateWithLifecycle()

    fun toast(message: suspend () -> String) {
        scope.launch {
            NuvioToastController.show(message(), durationMillis = 3000L, placement = NuvioToastPlacement.Bottom)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        NuvioShelfSection(
            title = stringResource(Res.string.iptv_pin_suggest_title, candidates.size),
            entries = candidates,
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            key = { it.contentId },
        ) { candidate ->
            val meta = MetaPreview(
                id = candidate.contentId,
                type = "tv",
                name = candidate.name,
                poster = candidate.logo,
                logo = candidate.logo,
                posterShape = PosterShape.Landscape,
            )
            LaunchedEffect(candidate.contentId, GuideDataRefreshPolicy.tileHealKey(epg[candidate.contentId]?.now != null, guideDataGeneration)) {
                XtreamHubRepository.ensureEpg(candidate.contentId)
            }
            Box(modifier = Modifier.width(tileWidth)) {
                XtreamLiveChannelTile(item = meta, epg = epg[candidate.contentId], onClick = { onPlay(meta) }, onLongClick = null)
                val pinDescription = stringResource(Res.string.iptv_pin_suggest_pin_cd, candidate.name)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(NuvioTokens.Space.s6)
                        .clip(RoundedCornerShape(50))
                        .background(tokens.colors.accent)
                        .semantics { contentDescription = pinDescription }
                        .clickable {
                            actions.pin(candidate)
                            toast { getString(Res.string.iptv_pin_suggest_pinned_one_toast, candidate.name) }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, tint = tokens.colors.onAccent, modifier = Modifier.size(NuvioTokens.Icon.xs))
                    Text(
                        stringResource(Res.string.iptv_pin_suggest_pin),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.onAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        // The one slim line: the quick yes, the quiet no, and where to switch it off for good.
        Row(
            modifier = Modifier.padding(horizontal = sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
                    .clickable {
                        val count = actions.pinAll(candidates)
                        toast { getString(Res.string.iptv_pin_suggest_pinned_all_toast, count) }
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(Res.string.iptv_pin_suggest_pin_all, candidates.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = actions::notNow) {
                Text(stringResource(Res.string.iptv_pin_suggest_not_now), color = tokens.colors.textSecondary)
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(Res.string.iptv_pin_suggest_turn_off), color = tokens.colors.textMuted)
            }
        }
    }
}
