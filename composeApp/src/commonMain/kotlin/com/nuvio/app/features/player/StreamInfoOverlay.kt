package com.nuvio.app.features.player

import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val StreamInfoRowHeight = 18.dp
private val StreamInfoRowGap = 2.dp

/**
 * A brief, glanceable readout of what is actually being decoded — resolution, codec,
 * bitrate — shown once when playback starts and then faded away.
 *
 * Deliberately the mirror image of [ParentalGuideOverlay]: same accent rule, same row
 * metrics, same stagger-in / hold / stagger-out timing, but anchored to the trailing edge
 * so the two can sit on opposite sides of the header without competing. Reusing that
 * vocabulary is the point — this is a second instance of an established treatment, not a
 * new one.
 */
@Composable
internal fun StreamInfoOverlay(
    lines: List<StreamInfoLine>,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(end = 32.dp, top = 24.dp),
) {
    if (lines.isEmpty()) return

    val count = lines.size
    val totalLineHeight = (StreamInfoRowHeight.value * count) +
        (StreamInfoRowGap.value * (count - 1))

    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(count) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible && !animating) {
            animating = true

            containerAlpha.animateTo(1f, tween(300))
            lineHeightFraction.animateTo(1f, tween(400, easing = FastOutSlowInEasing))

            for (i in 0 until count) {
                delay(80)
                itemAlphas[i].animateTo(1f, tween(200))
            }

            delay(5000)

            for (i in (count - 1) downTo 0) {
                delay(60)
                itemAlphas[i].animateTo(0f, tween(150))
            }

            delay(100)
            lineHeightFraction.animateTo(0f, tween(300, easing = FastOutSlowInEasing))

            delay(200)
            containerAlpha.animateTo(0f, tween(200))

            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    if (containerAlpha.value <= 0f) return

    Row(
        modifier = modifier
            .alpha(containerAlpha.value)
            .padding(contentPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.padding(end = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(StreamInfoRowGap),
        ) {
            lines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier
                        .height(StreamInfoRowHeight)
                        .alpha(itemAlphas.getOrNull(index)?.value ?: 0f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = line.primary,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    line.secondary?.let { secondary ->
                        Text(
                            text = " · ",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f),
                        )
                        Text(
                            text = secondary,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        // Trailing rule, so the block reads as anchored to the right edge — the parental
        // guide puts the identical rule on the leading edge.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height((totalLineHeight * lineHeightFraction.value).dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StreamInfoSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.replace("\n", " · "),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun formatResolution(width: Int, height: Int): String {
    val maxDimension = maxOf(width, height)
    val label = when {
        maxDimension >= 3600 -> "4K"
        maxDimension >= 2400 -> "1440p"
        maxDimension >= 1800 -> "1080p"
        maxDimension >= 1200 -> "720p"
        maxDimension >= 800 -> "480p"
        else -> "${minOf(width, height)}p"
    }
    return "$width × $height ($label)"
}

private fun formatBitrate(bitsPerSecond: Int): String =
    when {
        bitsPerSecond >= 1_000_000 -> "${formatDecimal(bitsPerSecond / 1_000_000.0, 1)} Mbps"
        bitsPerSecond >= 1_000 -> "${(bitsPerSecond / 1_000.0).roundToInt()} kbps"
        else -> "$bitsPerSecond bps"
    }

private fun formatChannelLayout(channels: Int): String =
    when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channels}ch"
    }

private fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return ((value * factor).roundToInt() / factor).toString().removeSuffix(".0")
}

@Composable
internal fun rememberStreamInfoSections(
    addonName: String,
    addonLogo: String?,
    streamName: String,
    streamDescription: String?,
    playbackEngine: AndroidPlaybackEngine?,
    serverPlayback: String?,
    mediaInfo: PlayerMediaInfo,
    audioTrack: AudioTrack?,
    subtitleTrack: SubtitleTrack?,
    addonSubtitle: AddonSubtitle?,
): List<PlayerControlInfoSection> {
    fun items(vararg pairs: Pair<String, String?>) =
        pairs.mapNotNull { (label, value) -> value?.let { PlayerControlInfoItem(label, it) } }

    val source = PlayerControlInfoSection(
        title = stringResource(Res.string.stream_info_section_source),
        logo = addonLogo?.takeIf { it.isNotBlank() },
        name = addonName,
        subtitle = streamName.takeIf { it.isNotBlank() && it != addonName },
        description = streamDescription?.takeIf { it.isNotBlank() }?.replace("\n", " · "),
        items = items(
            stringResource(Res.string.stream_info_player_engine) to playbackEngine?.label,
            stringResource(Res.string.stream_info_server) to serverPlayback,
        ),
    )
    val video = if (mediaInfo.videoCodec != null || mediaInfo.videoWidth != null || mediaInfo.videoFrameRate != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_video),
            items = items(
                stringResource(Res.string.stream_info_codec) to mediaInfo.videoCodec,
                stringResource(Res.string.stream_info_resolution) to
                    if (mediaInfo.videoWidth != null && mediaInfo.videoHeight != null) {
                        formatResolution(mediaInfo.videoWidth, mediaInfo.videoHeight)
                    } else null,
                stringResource(Res.string.stream_info_frame_rate) to
                    mediaInfo.videoFrameRate?.let { "${formatDecimal(it.toDouble(), 3)} fps" },
                stringResource(Res.string.stream_info_bitrate) to mediaInfo.videoBitrate?.let(::formatBitrate),
            ),
        )
    } else null
    val audio = if (mediaInfo.audioCodec != null || mediaInfo.audioChannels != null || audioTrack?.language != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_audio),
            items = items(
                stringResource(Res.string.stream_info_codec) to mediaInfo.audioCodec,
                stringResource(Res.string.stream_info_channels) to mediaInfo.audioChannels?.let(::formatChannelLayout),
                stringResource(Res.string.stream_info_sample_rate) to
                    mediaInfo.audioSampleRate?.let { "${formatDecimal(it / 1000.0, 1)} kHz" },
                stringResource(Res.string.stream_info_language) to
                    audioTrack?.language?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(it) },
            ),
        )
    } else null
    val subtitle = if (addonSubtitle != null || subtitleTrack != null) {
        PlayerControlInfoSection(
            title = stringResource(Res.string.stream_info_section_subtitle),
            items = items(
                stringResource(Res.string.stream_info_name) to (
                    addonSubtitle?.display ?: subtitleTrack?.let {
                        localizedTrackDisplayName(it.label, it.language, it.index)
                    }
                    ),
                stringResource(Res.string.stream_info_language) to
                    (addonSubtitle?.language ?: subtitleTrack?.language)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { languageLabelForCode(it) },
                stringResource(Res.string.stream_info_source) to stringResource(
                    if (addonSubtitle != null) {
                        Res.string.stream_info_subtitle_source_addon
                    } else {
                        Res.string.stream_info_subtitle_source_embedded
                    },
                ),
            ),
        )
    } else null
    return listOfNotNull(source, video, audio, subtitle)
}
