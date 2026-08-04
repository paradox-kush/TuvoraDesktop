package com.nuvio.app.features.player

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

/** One row: a bright primary fact and an optional dimmer qualifier, as `1080p · HEVC`. */
data class StreamInfoLine(
    val primary: String,
    val secondary: String? = null,
)
