package com.nuvio.app.features.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.nuvio.app.features.common.lifecycle.EffectScope
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What a player engine is handed for one source: the source URL itself and a mapping for every other URL of the same server
 * (external subtitle files). For a direct source [rewrite] returns its input; for a source on a pinned (self-signed)
 * server both go through the verified loopback relay ([PinnedMediaRelay]).
 */
internal class PinnedPlayback(val url: String, private val session: RelaySession?) {
    /** The URL an engine should fetch for [original]: the loopback twin when it is on the pinned server, else unchanged. */
    fun rewrite(original: String): String = session?.rewrite(original) ?: original

    fun rewriteSubtitles(subtitles: List<StreamSubtitle>): List<StreamSubtitle> =
        if (session == null) subtitles else subtitles.map { it.copy(url = rewrite(it.url)) }

    val isRelayed: Boolean get() = session != null
}

/**
 * The [PinnedPlayback] for [sourceUrl]. A direct URL (CA-signed servers, add-ons, IPTV) is returned synchronously and
 * untouched. A source on a server this device pinned a certificate for is served through a loopback relay session owned by
 * this composition (an [EffectScope], disposed when the player leaves); until that session is listening this returns null and
 * the caller renders nothing yet; if the listener cannot be opened [onFailed] is called (the caller reports a playback error
 * instead of leaving a blank player).
 */
@Composable
internal fun rememberPinnedPlayback(
    sourceUrl: String,
    isVerifiedServerHost: ((authority: String) -> Boolean)? = null,
    onFailed: (Throwable) -> Unit = {},
): PinnedPlayback? {
    // Default (mobile engines): only a server this device pinned needs the relay. A platform whose player does not verify
    // TLS itself (Desktop libmpv) also relays every media-server host so the stream's credential never rides unverified TLS.
    val route = if (isVerifiedServerHost == null) {
        PinnedMediaPolicy.route(sourceUrl)
    } else {
        PinnedMediaPolicy.playbackRoute(sourceUrl, { ServerTrust.shared.pinnedFingerprint(it) }, isVerifiedServerHost)
    }
    val authority = (route as? PinnedMediaPolicy.Route.Pinned)?.authority
    val latestOnFailed = rememberUpdatedState(onFailed)
    var session by remember(authority) { mutableStateOf<RelaySession?>(null) }
    DisposableEffect(authority) {
        if (authority == null) return@DisposableEffect onDispose { }
        val scope = EffectScope(onRevertFailure = {})
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val opened = PinnedMediaRelay.shared.open(authority)
                scope.onRevert { opened.dispose() }
                session = opened
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                latestOnFailed.value(error)
            }
        }
        onDispose {
            job.cancel()
            scope.dispose()
            session = null
        }
    }
    val opened = session
    return remember(sourceUrl, opened) {
        when {
            authority == null -> PinnedPlayback(sourceUrl, null)
            opened == null -> null
            else -> opened.rewrite(sourceUrl)?.let { PinnedPlayback(it, opened) }
        }
    }
}
