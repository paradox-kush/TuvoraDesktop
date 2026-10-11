package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import com.nuvio.app.core.contracts.PlaybackTrustHosts
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.streams.PlaybackTlsClass
import com.nuvio.app.features.streams.PlaybackTlsPolicy
import com.nuvio.app.features.servers.rememberPinnedPlayback
import com.nuvio.app.features.servers.serverAuthority
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_unable_to_play_stream
import nuvio.composeapp.generated.resources.servers_playback_failed
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.drop

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    isCatchUpPlayback: Boolean,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    videoZoom: VideoZoom,
    playbackEngine: com.nuvio.app.features.player.AndroidPlaybackEngine?,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    sourceAvailable: Boolean,
) {
    // A source on a pinned (self-signed) media server reaches libmpv through a verified loopback relay owned by this
    // composition; its subtitle files map onto the same session. Every other source (IPTV, CA-signed servers) is returned
    // untouched. Null = the relay is still opening: render nothing yet, never hand libmpv the raw https url.
    val relayFailedMessage = stringResource(Res.string.servers_playback_failed)
    // The bundled libmpv does not verify TLS by default (tls-verify=no), so every media-server host goes through the relay too.
    val pinned = rememberPinnedPlayback(
        sourceUrl,
        isVerifiedServerHost = { authority -> ServerRepository.uiState.value.connections.any { serverAuthority(it.address) == authority } },
    ) { onError(relayFailedMessage) } ?: return
    val engineOnControllerReady = remember(onControllerReady, pinned) {
        if (!pinned.isRelayed) onControllerReady else { controller: PlayerEngineController ->
            onControllerReady(RelayedSubtitleController(controller, pinned::rewrite))
        }
    }
    val sourceUrl = pinned.url
    // The bundled libmpv does not verify TLS unless told to (macOS bridge: now yes by default). Only a source on one of the
    // user's IPTV panels (PlaybackTlsPolicy.PermissivePanel) keeps the old unverified behaviour; everything else is verified.
    val tlsVerify = remember(sourceUrl) { playbackTlsClassOf(sourceUrl) == PlaybackTlsClass.Strict }
    val onControllerReady = engineOnControllerReady
    if (DesktopHostOs.current == DesktopHostOs.MACOS ||
        DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.LINUX
    ) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            tlsVerify = tlsVerify,
            sourceAvailable = sourceAvailable,
            isLiveStream = LivePlaybackRejoinPolicy.rejoinsLiveEdge(streamType, isCatchUpPlayback),
            sourceHeaders = sourceHeaders,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            videoZoom = videoZoom,
            // F13: the user's live buffer length (null = the bridge's defaults; never VOD/catch-up).
            liveBufferPlan = LiveBufferPolicy.planFor(
                seconds = PlayerSettingsRepository.uiState.value.liveBufferSeconds,
                isLive = com.nuvio.app.features.streams.normalizeStreamType(streamType) == "live",
                isCatchUp = isCatchUpPlayback,
            ),
            initialPositionMs = initialPositionMs ?: 0L,
            initialPositionRequestKey = initialPositionRequestKey,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        initialPositionRequestKey = initialPositionRequestKey,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    tlsVerify: Boolean,
    sourceAvailable: Boolean,
    isLiveStream: Boolean,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    videoZoom: VideoZoom,
    liveBufferPlan: LiveBufferPlan?,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val platformDensity = LocalNuvioPlatformDensity.current
    val host = remember { NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val latestPlayerControlsState = rememberUpdatedState(playerControlsState)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled

    SideEffect {
        onControllerReady(controller)
    }

    DisposableEffect(host) {
        DesktopPlayerPictureInPicture.setHost(host)
        DesktopPlayerPictureInPicture.setController(controller)
        host.onDisplayableChanged = { displayable ->
            if (!displayable) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
        }
        onDispose {
            DesktopPlayerPictureInPicture.release()
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceAvailable, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    // The controls overlay owns the player shortcuts. After alt-tab, desktop
    // window focus can return to the AWT/Compose host instead of the embedded
    // WebView, so explicitly hand keyboard focus back to the native controls
    // whenever the player window becomes active again.
    DisposableEffect(controller, hostFirstFullSizePaintComplete.value) {
        val uninstall = if (hostFirstFullSizePaintComplete.value) {
            controller.installWindowFocusForwarding()
        } else {
            null
        }
        onDispose { uninstall?.invoke() }
    }

    LaunchedEffect(
        controller,
        sourceAvailable,
        sourceUrl,
        playbackHeaders,
        decoderPriority,
        nvidiaRtxSuperResolutionEnabled,
        hostFirstFullSizePaintComplete.value,
        initialPositionMs,
        initialPositionRequestKey,
    ) {
        if (!sourceAvailable || !hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            tlsVerify = tlsVerify,
            onError = { message -> latestOnError.value(message) },
        )
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, initialPositionMs > 0L)
        }
        onControllerReady(controller)
    }

    LaunchedEffect(controller, sourceAvailable, playWhenReady) {
        if (!sourceAvailable) return@LaunchedEffect
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, videoZoom) {
        controller.setVideoZoom(videoZoom)
    }

    LaunchedEffect(controller, liveBufferPlan) {
        controller.setLiveBuffer(liveBufferPlan)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
        DesktopPlayerPictureInPicture.setWindowTitle(playerControlsState.pipWindowTitle)
    }

    LaunchedEffect(controller) {
        DesktopPlayerPictureInPicture.changes.drop(1).collect {
            controller.updateControls(latestPlayerControlsState.value)
        }
    }

    LaunchedEffect(controller) {
        AppPresenceState.current.collect { snapshot ->
            val player = snapshot as? PresenceSnapshot.Player
            controller.setNowPlayingMetadata(player?.title, player?.episodeLabel, player?.posterUrl)
        }
    }

    LaunchedEffect(controller) {
        desktopFullscreenChanges.drop(1).collect {
            controller.onDesktopFullscreenChanged()
        }
    }

    // A live END_FILE (failed open / dropped stream) reaches the screen as an ended snapshot.
    val latestIsLiveStream = rememberUpdatedState(isLiveStream)
    val latestOnErrorForOpen = rememberUpdatedState(onError)
    val openFailedMessage = stringResource(Res.string.player_error_unable_to_play_stream)
    LaunchedEffect(controller) {
        var openFailureReported = false
        while (true) {
            val snapshot = controller.liveAwareSnapshot(latestIsLiveStream.value)
            onSnapshot(snapshot)
            // A film whose open failed (refused certificate, 404, unreachable host) never leaves "loading" in the native
            // snapshot: say so once instead of spinning forever.
            val failed = controller.vodOpenFailed(snapshot, latestIsLiveStream.value)
            if (failed && !openFailureReported) latestOnErrorForOpen.value(openFailedMessage)
            openFailureReported = failed
            delay(500L)
        }
    }

    val pipChanges by DesktopPlayerPictureInPicture.changes.collectAsState()
    val isInPip = pipChanges >= 0 && DesktopPlayerPictureInPicture.isEnabled

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // SwingPanel keeps the stable AWT host. PiP moves only the native player
        // surface to the independent window, so Compose never loses its host peer.
        CompositionLocalProvider(LocalDensity provides platformDensity) {
            SwingPanel(
                factory = { host },
                modifier = if (isInPip) {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .requiredSize(1.dp)
                } else if (hostFirstPaintComplete.value) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.BottomEnd)
                        .requiredSize(1.dp)
                },
                background = Color.Black,
            )
        }

        // Placeholder overlay shown when video has moved to PiP window
        if (isInPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = playerControlsState.pipPlaceholderTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { DesktopPlayerPictureInPicture.clear() },
                    ) {
                        Text(playerControlsState.pipRestoreLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    LaunchedEffect(initialPositionRequestKey) {
        initialPositionRequestKey?.let { key -> onInitialPositionHandled(key, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}

/**
 * How strictly libmpv validates TLS for [sourceUrl] (pure policy + the two live host sources): IPTV panel hosts may stay
 * unverified, a configured media-server host never, everything else (add-ons, debrid CDNs, unknown hosts) is verified.
 */
private fun playbackTlsClassOf(sourceUrl: String): PlaybackTlsClass {
    val host = runCatching { io.ktor.http.Url(sourceUrl).host }.getOrNull()
    return PlaybackTlsPolicy.classify(
        host = host,
        isPanelHost = PlaybackTrustHosts::isPanelHost,
        isMediaServerHost = { h ->
            ServerRepository.uiState.value.connections.any { c ->
                PlaybackTlsPolicy.normalize(runCatching { io.ktor.http.Url(c.address).host }.getOrNull()) == h
            }
        },
    )
}
