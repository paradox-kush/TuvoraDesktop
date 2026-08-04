package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.stream_info_bitrate_kbps
import nuvio.composeapp.generated.resources.stream_info_bitrate_mbps
import nuvio.composeapp.generated.resources.stream_info_channels_mono
import nuvio.composeapp.generated.resources.stream_info_channels_other
import nuvio.composeapp.generated.resources.stream_info_channels_stereo
import nuvio.composeapp.generated.resources.stream_info_frame_rate_value
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource

/**
 * Condense [PlayerStreamInfo] into at most three glanceable rows:
 *
 * ```
 *   1080p · HEVC
 * 6.4 Mbps · 50 fps
 *      5.1 · E-AC-3
 * ```
 *
 * A row is dropped entirely when its primary fact is unknown, and the qualifier is
 * dropped on its own when only that is missing. Live MPEG-TS in particular declares very
 * little, so a one-row overlay is a normal outcome, not a bug.
 */
@Composable
internal fun rememberStreamInfoLines(streamInfo: PlayerStreamInfo): List<StreamInfoLine> {
    val quality = StreamInfoFormat.qualityLabel(streamInfo.videoWidth, streamInfo.videoHeight)
    val frameRate = StreamInfoFormat.frameRate(streamInfo.videoFrameRate)
        ?.let { stringResource(Res.string.stream_info_frame_rate_value, it) }
    val videoBitrate = streamInfo.videoBitrate.bitrateLabel()
    val channels = streamInfo.audioChannelCount.channelLabel()

    return buildList {
        // Video identity: what quality am I actually getting, and in what codec.
        val videoPrimary = quality ?: streamInfo.videoCodec
        if (videoPrimary != null) {
            add(
                StreamInfoLine(
                    primary = videoPrimary,
                    secondary = streamInfo.videoCodec.takeIf { quality != null },
                ),
            )
        }
        // Throughput: the number people actually mean when they say "bitrate".
        if (videoBitrate != null) {
            add(StreamInfoLine(primary = videoBitrate, secondary = frameRate))
        } else if (frameRate != null) {
            add(StreamInfoLine(primary = frameRate))
        }
        // Audio: layout first, since "5.1 or stereo?" is the common question.
        val audioPrimary = channels ?: streamInfo.audioCodec
        if (audioPrimary != null) {
            add(
                StreamInfoLine(
                    primary = audioPrimary,
                    secondary = streamInfo.audioCodec.takeIf { channels != null },
                ),
            )
        }
    }
}

@Composable
private fun Int?.bitrateLabel(): String? {
    val bitrate = StreamInfoFormat.bitrate(this) ?: return null
    return if (bitrate.isMegabits) {
        stringResource(Res.string.stream_info_bitrate_mbps, bitrate.value)
    } else {
        stringResource(Res.string.stream_info_bitrate_kbps, bitrate.value)
    }
}

@Composable
private fun Int?.channelLabel(): String? {
    val count = this?.takeIf { it > 0 } ?: return null
    StreamInfoFormat.channelLayout(count)?.let { return it }
    return when (count) {
        1 -> stringResource(Res.string.stream_info_channels_mono)
        2 -> stringResource(Res.string.stream_info_channels_stereo)
        else -> stringResource(Res.string.stream_info_channels_other, count)
    }
}
