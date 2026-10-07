package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.mediaserver.internal.policy.MintFailurePolicy
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ms_play_server_error
import nuvio.composeapp.generated.resources.ms_play_sign_in_again
import nuvio.composeapp.generated.resources.ms_play_timed_out
import nuvio.composeapp.generated.resources.ms_play_unavailable
import nuvio.composeapp.generated.resources.ms_play_unreachable
import org.jetbrains.compose.resources.getString

/**
 * What the viewer hears when a media-server stream could not be started. Wording is deliberately about "the server" and
 * "this title" - never about where the server gets its files. Every pick site used to fall silently back to the list.
 */
internal object MediaServerMintNotices {
    fun show(reason: MintFailurePolicy.Reason) {
        val message = runBlocking {
            when (reason) {
                MintFailurePolicy.Reason.SOURCE_UNAVAILABLE -> getString(Res.string.ms_play_unavailable)
                MintFailurePolicy.Reason.TIMED_OUT -> getString(Res.string.ms_play_timed_out)
                MintFailurePolicy.Reason.UNREACHABLE -> getString(Res.string.ms_play_unreachable)
                MintFailurePolicy.Reason.SIGN_IN_AGAIN -> getString(Res.string.ms_play_sign_in_again)
                MintFailurePolicy.Reason.SERVER_ERROR -> getString(Res.string.ms_play_server_error)
            }
        }
        NuvioToastController.show(message, durationMillis = 4000L)
    }
}
