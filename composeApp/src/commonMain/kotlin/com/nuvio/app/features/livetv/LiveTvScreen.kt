package com.nuvio.app.features.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.player.EnterImmersivePlayerMode
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerStreamInfo
import com.nuvio.app.features.player.StreamInfoOverlay
import com.nuvio.app.features.player.rememberStreamInfoLines
import kotlinx.coroutines.delay
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.compose_iptv_hub_epg_next
import nuvio.composeapp.generated.resources.compose_livetv_error_tap_retry
import nuvio.composeapp.generated.resources.compose_livetv_exit_fullscreen
import nuvio.composeapp.generated.resources.compose_livetv_fullscreen
import nuvio.composeapp.generated.resources.compose_livetv_live_badge
import nuvio.composeapp.generated.resources.compose_livetv_no_programme_info
import nuvio.composeapp.generated.resources.compose_livetv_play_pause
import org.jetbrains.compose.resources.stringResource

/** Share of the window height the docked player takes when the window is wider than it is tall. */
private const val DOCKED_PLAYER_HEIGHT_FRACTION = 0.58f

/**
 * Dedicated Live TV experience. Portrait shows a docked 16:9 player over an EPG timeline guide;
 * landscape (via rotation or the fullscreen button) fills the screen with the video. Channel taps
 * in the guide switch playback in place — no re-navigation.
 */
@Composable
fun LiveTvScreen(
    initialContentId: String,
    initialTitle: String,
    initialLogo: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors

    var currentContentId by remember { mutableStateOf(initialContentId) }
    var currentTitle by remember { mutableStateOf(initialTitle) }
    var currentLogo by remember { mutableStateOf(initialLogo) }

    var source by remember { mutableStateOf<LiveChannelSource?>(null) }
    var resolveError by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    // Live TV hosts its own player surface rather than going through PlayerScreen, so the
    // stream readout has to be wired up here too — this is the surface users actually ask
    // "what resolution is this channel?" about.
    var streamInfo by remember { mutableStateOf(PlayerStreamInfo()) }
    var showStreamInfo by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<LiveGuideChannel>>(emptyList()) }
    val programmes = remember { mutableStateMapOf<String, List<XtreamProgram>>() }
    val requestedProgrammes = remember { mutableSetOf<String>() }

    var nowMs by remember { mutableStateOf(TraktPlatformClock.nowEpochMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = TraktPlatformClock.nowEpochMs()
            delay(30_000)
        }
    }

    // Resolve (or re-resolve on channel switch / retry) the playable source.
    LaunchedEffect(currentContentId, retryTick) {
        source = null
        resolveError = false
        playbackError = null
        val resolved = LiveTvData.resolveSource(currentContentId, currentTitle, currentLogo)
        if (resolved == null) resolveError = true else source = resolved
    }
    // Always re-resolve on retry: live links carry expiring tokens (Stalker create_link is
    // single-use/short-TTL), so controller.retry() would just replay the dead URL.
    val onRetry: () -> Unit = { retryTick++ }

    // One AUTOMATIC fresh re-resolve per resolved URL: a mid-watch 401 (token expired, or the
    // portal session was rotated by another device on the same MAC) recovers invisibly; a second
    // failure on the freshly minted link means the channel/account is the problem — surface the
    // error pill instead of hammering the portal.
    var autoRefreshBurntUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playbackError) {
        val failedUrl = source?.url ?: return@LaunchedEffect
        if (playbackError != null && autoRefreshBurntUrl != failedUrl) {
            autoRefreshBurntUrl = failedUrl
            retryTick++
        }
    }

    // Guide channel column (once, from the launch channel's account).
    LaunchedEffect(initialContentId) {
        channels = LiveTvData.guideChannels(initialContentId)
    }

    // Load programmes for any channel that asks (lazy, cached, de-duped).
    val onNeedProgrammes: (String) -> Unit = { contentId ->
        if (requestedProgrammes.add(contentId)) {
            scope.launch {
                val list = LiveTvData.programmes(contentId)
                if (list.isNotEmpty()) programmes[contentId] = list
            }
        }
    }
    // Always keep the current channel's programmes warm for the now-bar.
    LaunchedEffect(currentContentId) { onNeedProgrammes(currentContentId) }

    fun switchTo(channel: LiveGuideChannel) {
        if (channel.contentId == currentContentId) return
        currentContentId = channel.contentId
        currentTitle = channel.name
        currentLogo = channel.logo
    }

    // ---- Orientation / fullscreen state ----
    val physicalLandscape by rememberPhysicalLandscape()
    var manualOrientation by remember { mutableStateOf<Boolean?>(null) } // true=landscape,false=portrait,null=follow
    // Hand control back to the sensor once the device physically agrees with a forced rotation.
    LaunchedEffect(physicalLandscape, manualOrientation) {
        val manual = manualOrientation
        val physical = physicalLandscape
        if (manual != null && physical != null && physical == manual) {
            manualOrientation = null
        }
    }
    val orientationMode = when (manualOrientation) {
        true -> LiveOrientationMode.ForceLandscape
        false -> LiveOrientationMode.ForcePortrait
        null -> LiveOrientationMode.Sensor
    }
    ApplyLiveOrientation(orientationMode)

    val nowNext = remember(programmes[currentContentId], nowMs) {
        nowNextOf(programmes[currentContentId], nowMs)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(colors.surface),
    ) {
        // Rotation drives fullscreen where the window follows the device. Desktop windows are
        // landscape at every size, so there fullscreen is whatever the user last asked for —
        // otherwise the guide below would never get a chance to render.
        var toggledFullscreen by remember { mutableStateOf(false) }
        val wideWindow = maxWidth > maxHeight
        val fullscreen =
            if (LiveTvFullscreenFollowsWindowAspect) wideWindow else toggledFullscreen
        val dockedPlayerHeight = maxHeight * DOCKED_PLAYER_HEIGHT_FRACTION
        val hasError = resolveError || playbackError != null

        fun setFullscreen(enabled: Boolean) {
            if (LiveTvFullscreenFollowsWindowAspect) manualOrientation = enabled
            else toggledFullscreen = enabled
        }

        // Back in fullscreen exits fullscreen instead of leaving the screen.
        PlatformBackHandler(enabled = fullscreen) { setFullscreen(false) }
        // Immersive mode hides the system bars, so on a phone it belongs to fullscreen alone. On
        // desktop it is only a display-sleep inhibitor and docked is the normal way to watch, so
        // it stays on there whenever the screen is up — otherwise the monitor sleeps mid-channel.
        if (fullscreen || !LiveTvFullscreenFollowsWindowAspect) {
            EnterImmersivePlayerMode(keepScreenAwake = snapshot.isPlaying || snapshot.isLoading)
        }

        // Fullscreen controls auto-hide while playing; always shown when docked.
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(fullscreen) { controlsVisible = true }
        LaunchedEffect(controlsVisible, fullscreen, snapshot.isPlaying) {
            if (fullscreen && controlsVisible && snapshot.isPlaying) {
                delay(3500)
                controlsVisible = false
            }
        }
        val showPlayPause = !hasError && !(snapshot.isLoading && !snapshot.isPlaying)

        Column(
            modifier = Modifier.fillMaxSize().then(
                if (fullscreen) Modifier else Modifier.statusBarsPadding(),
            ),
        ) {
            // The player box keeps a STABLE position (always the Column's first child); only its size
            // modifier changes between docked and fullscreen (fill). The MPV SurfaceView is
            // therefore never detached/reattached on rotation, so the stream doesn't reload/rebuffer.
            //
            // Docked sizing differs by shape: a 16:9 box is the right dock over a portrait phone,
            // but the full width of a landscape desktop window would push the guide off-screen, so
            // there the dock is capped to a fraction of the height and the video letterboxes.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            fullscreen -> Modifier.weight(1f)
                            wideWindow -> Modifier.height(dockedPlayerHeight)
                            else -> Modifier.aspectRatio(16f / 9f)
                        },
                    )
                    .background(Color.Black)
                    .then(
                        if (fullscreen) Modifier.clickable { controlsVisible = !controlsVisible } else Modifier,
                    ),
            ) {
                LivePlayerSurface(
                    source = source,
                    onControllerReady = { controller = it },
                    onSnapshot = { snapshot = it },
                    onError = { playbackError = it },
                )

                // Re-read per channel: switching channels in place keeps this composable
                // alive, so keying only on isPlaying would show the first channel's facts.
                LaunchedEffect(snapshot.isPlaying, source) {
                    if (!snapshot.isPlaying) return@LaunchedEffect
                    if (!PlayerSettingsRepository.uiState.value.showStreamInfo) return@LaunchedEffect
                    // mpv's video-bitrate is a throttled rolling estimate measured over
                    // keyframe intervals — at first frame it is still 0. Let it settle so
                    // the bitrate row isn't dropped on every live channel.
                    delay(STREAM_INFO_SETTLE_MS)
                    val info = controller?.getStreamInfo() ?: return@LaunchedEffect
                    if (!info.hasAnyValue) return@LaunchedEffect
                    streamInfo = info
                    showStreamInfo = true
                }

                StreamInfoOverlay(
                    lines = rememberStreamInfoLines(streamInfo),
                    isVisible = showStreamInfo,
                    onAnimationComplete = { showStreamInfo = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                    // Sits below the fullscreen button and the LIVE badge, which own the
                    // top edge of the dock.
                    contentPadding = PaddingValues(end = 16.dp, top = 76.dp),
                )

                // Loading / error indicators (both orientations).
                when {
                    hasError -> Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorPill(colors.danger, onRetry) }
                    snapshot.isLoading && !snapshot.isPlaying ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                }

                if (fullscreen) {
                    FullscreenControls(
                        visible = controlsVisible,
                        title = currentTitle,
                        isPlaying = snapshot.isPlaying,
                        showPlayPause = showPlayPause,
                        danger = colors.danger,
                        onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                        onExitFullscreen = { setFullscreen(false) },
                        onBack = onBack,
                    )
                } else {
                    DockedPlayerOverlay(
                        isPlaying = snapshot.isPlaying,
                        showPlayPause = showPlayPause,
                        danger = colors.danger,
                        onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                        onEnterFullscreen = { setFullscreen(true) },
                        onBack = onBack,
                    )
                }
            }

            if (!fullscreen) {
                NowBar(logo = currentLogo, title = currentTitle, nowNext = nowNext, nowMs = nowMs, colors = colors)
                LiveGuideGrid(
                    channels = channels,
                    currentContentId = currentContentId,
                    nowMs = nowMs,
                    programmesOf = { programmes[it] },
                    onNeedProgrammes = onNeedProgrammes,
                    onSelectChannel = ::switchTo,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** now + next titles for the current channel, for the docked now-bar. */
private data class NowNext(val now: XtreamProgram?, val next: XtreamProgram?)

private fun nowNextOf(list: List<XtreamProgram>?, nowMs: Long): NowNext {
    if (list.isNullOrEmpty()) return NowNext(null, null)
    val sorted = list.sortedBy { it.startMs }
    val nowIdx = sorted.indexOfFirst { nowMs in it.startMs until it.endMs }
        .takeIf { it >= 0 }
        ?: sorted.indexOfFirst { it.startMs > nowMs }.takeIf { it >= 0 }?.let { it - 1 }
        ?: 0
    return NowNext(sorted.getOrNull(nowIdx), sorted.getOrNull(nowIdx + 1))
}

@Composable
private fun NowBar(
    logo: String?,
    title: String,
    nowNext: NowNext,
    nowMs: Long,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                .background(colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nowNext.now?.title ?: title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val nextLabel = nowNext.next?.let { stringResource(Res.string.compose_iptv_hub_epg_next, it.title) }
            val timeLabel = nowNext.now?.let { "${liveClockLabel(it.startMs)} – ${liveClockLabel(it.endMs)}" }
            val subtitle = listOfNotNull(timeLabel, nextLabel).joinToString("   ")
                .ifBlank { stringResource(Res.string.compose_livetv_no_programme_info) }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Portrait overlay: back + LIVE + fullscreen button on top, play/pause centered. */
@Composable
private fun DockedPlayerOverlay(
    isPlaying: Boolean,
    showPlayPause: Boolean,
    danger: Color,
    onPlayPause: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back), onBack)
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            LiveBadge(danger)
            Spacer(Modifier.weight(1f))
            OverlayIconButton(Icons.Filled.Fullscreen, stringResource(Res.string.compose_livetv_fullscreen), onEnterFullscreen)
        }
        if (showPlayPause) {
            OverlayIconButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(Res.string.compose_livetv_play_pause),
                onPlayPause,
                big = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Landscape overlay (fades in/out): back + LIVE + title on top, play/pause centered, minimise bottom-right. */
@Composable
private fun FullscreenControls(
    visible: Boolean,
    title: String,
    isPlaying: Boolean,
    showPlayPause: Boolean,
    danger: Color,
    onPlayPause: () -> Unit,
    onExitFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back), onBack)
            Spacer(Modifier.width(NuvioTokens.Space.s12))
            LiveBadge(danger)
            Spacer(Modifier.width(NuvioTokens.Space.s12))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showPlayPause) {
            OverlayIconButton(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(Res.string.compose_livetv_play_pause),
                onPlayPause,
                big = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(NuvioTokens.Space.s16),
        ) {
            OverlayIconButton(Icons.Filled.FullscreenExit, stringResource(Res.string.compose_livetv_exit_fullscreen), onExitFullscreen)
        }
    }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun LivePlayerSurface(
    source: LiveChannelSource?,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val current = source ?: return
    // Key by url so a channel switch cleanly re-initialises the engine.
    androidx.compose.runtime.key(current.url) {
        PlatformPlayerSurface(
            sourceUrl = current.url,
            sourceHeaders = current.headers,
            streamType = "live",
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            resizeMode = PlayerResizeMode.Fit,
            useNativeController = false,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    }
}

@Composable
private fun LiveBadge(danger: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(danger)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text(
            text = stringResource(Res.string.compose_livetv_live_badge),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorPill(danger: Color, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onRetry)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = danger)
        Text(
            text = stringResource(Res.string.compose_livetv_error_tap_retry),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    big: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(if (big) 64.dp else 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(if (big) 36.dp else 22.dp),
        )
    }
}

/** How long to let mpv measure a bitrate before reading the stream info. */
private const val STREAM_INFO_SETTLE_MS = 2500L
