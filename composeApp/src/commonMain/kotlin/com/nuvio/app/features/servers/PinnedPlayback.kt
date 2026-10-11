package com.nuvio.app.features.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.common.lifecycle.EffectScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The URL a player engine should be given for [sourceUrl]. A direct URL (CA-signed servers, add-ons, IPTV) is returned
 * untouched and synchronously. A source on a server this device pinned a certificate for is served through a loopback
 * [PinnedMediaRelay] session owned by this composition (an [EffectScope], disposed when the player leaves); until that
 * session is listening this returns null and the caller renders nothing yet; if the listener cannot be opened [onFailed] is called
 * (the caller reports a playback error instead of leaving a blank player).
 */
@Composable
internal fun rememberPinnedPlaybackUrl(sourceUrl: String, onFailed: (Throwable) -> Unit = {}): String? =
    rememberPinnedPlayback(sourceUrl, onFailed).url

/**
 * [rememberPinnedPlaybackUrl] plus [PinnedPlayback.rewrite]: sibling URLs of the same pinned server (a server-provided subtitle
 * file) that the player is handed later must go through the same session.
 */
internal class PinnedPlayback internal constructor(val url: String?, private val session: RelaySession?) {
    /** The loopback twin of [other] when it belongs to this session's server, else null (play/fetch it as it is). */
    fun rewrite(other: String): String? = session?.rewrite(other)
}

@Composable
internal fun rememberPinnedPlayback(sourceUrl: String, onFailed: (Throwable) -> Unit = {}): PinnedPlayback {
    val route = PinnedMediaPolicy.route(sourceUrl)
    if (route !is PinnedMediaPolicy.Route.Pinned) return PinnedPlayback(sourceUrl, null)
    val authority = route.authority
    val latestOnFailed = androidx.compose.runtime.rememberUpdatedState(onFailed)
    var session by remember(authority) { mutableStateOf<RelaySession?>(null) }
    DisposableEffect(authority) {
        val scope = EffectScope(onRevertFailure = {})
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val opened = PinnedMediaRelay.shared.open(authority)
                scope.onRevert { opened.dispose() }
                session = opened
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Never a silent black screen: the caller surfaces a playback error.
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
    return PinnedPlayback(opened?.rewrite(sourceUrl), opened)
}
