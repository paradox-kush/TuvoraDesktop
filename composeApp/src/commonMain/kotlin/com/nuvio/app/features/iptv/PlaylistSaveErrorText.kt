package com.nuvio.app.features.iptv

import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_playlist_error_file_unreadable
import nuvio.composeapp.generated.resources.iptv_playlist_error_invalid_address
import nuvio.composeapp.generated.resources.iptv_playlist_error_missing_m3u_file
import nuvio.composeapp.generated.resources.iptv_playlist_error_missing_m3u_url
import nuvio.composeapp.generated.resources.iptv_playlist_error_missing_stalker_fields
import nuvio.composeapp.generated.resources.iptv_playlist_error_missing_xtream_fields
import nuvio.composeapp.generated.resources.iptv_playlist_error_secure_connection
import nuvio.composeapp.generated.resources.iptv_playlist_error_unreachable
import nuvio.composeapp.generated.resources.iptv_playlist_error_wrong_credentials
import nuvio.composeapp.generated.resources.iptv_playlist_saved_unverified
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The localized sentence for a [PlaylistSaveMessage]. Falls back to the approved English wording
 * ([PlaylistSaveError.fallbackText]) wherever resources can't be read (unit tests, a headless host).
 */
internal suspend fun PlaylistSaveMessage.text(): String = when (this) {
    is PlaylistSaveMessage.Authored -> text
    is PlaylistSaveMessage.Known -> error.text()
}

internal suspend fun PlaylistSaveError.text(): String = resourceOr(fallbackText) { getString(resource()) }

/** The playlist-row warning when an edit was saved but its live check failed (B60 + UX11). */
internal suspend fun playlistSavedUnverifiedWarning(failure: PlaylistSaveMessage): String {
    val reason = failure.text()
    return resourceOr("Saved, but the provider couldn't be checked: $reason") {
        getString(Res.string.iptv_playlist_saved_unverified, reason)
    }
}

private fun PlaylistSaveError.resource(): StringResource = when (this) {
    PlaylistSaveError.MISSING_XTREAM_FIELDS -> Res.string.iptv_playlist_error_missing_xtream_fields
    PlaylistSaveError.MISSING_M3U_URL -> Res.string.iptv_playlist_error_missing_m3u_url
    PlaylistSaveError.MISSING_STALKER_FIELDS -> Res.string.iptv_playlist_error_missing_stalker_fields
    PlaylistSaveError.MISSING_M3U_FILE -> Res.string.iptv_playlist_error_missing_m3u_file
    PlaylistSaveError.INVALID_ADDRESS -> Res.string.iptv_playlist_error_invalid_address
    PlaylistSaveError.UNREACHABLE -> Res.string.iptv_playlist_error_unreachable
    PlaylistSaveError.WRONG_CREDENTIALS -> Res.string.iptv_playlist_error_wrong_credentials
    PlaylistSaveError.SECURE_CONNECTION_FAILED -> Res.string.iptv_playlist_error_secure_connection
    PlaylistSaveError.FILE_UNREADABLE -> Res.string.iptv_playlist_error_file_unreadable
}

private suspend fun resourceOr(fallback: String, read: suspend () -> String): String = try {
    read().takeIf { it.isNotBlank() } ?: fallback
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fallback
}
