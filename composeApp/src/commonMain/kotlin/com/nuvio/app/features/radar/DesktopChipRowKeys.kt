package com.nuvio.app.features.radar

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Desktop keyboard layer for a row of chips: Left/Right move focus between chips ([DesktopFocusOrderPolicy] decides),
 * Tab / Shift+Tab / Enter keep Compose's own traversal and the chip's own click. A no-op for touch (no hardware keys).
 */
fun Modifier.desktopChipKeys(index: Int, requesters: List<FocusRequester>): Modifier =
    focusRequester(requesters[index]).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val key = when (event.key) {
            Key.DirectionLeft -> DesktopFocusOrderPolicy.Key.LEFT
            Key.DirectionRight -> DesktopFocusOrderPolicy.Key.RIGHT
            else -> return@onPreviewKeyEvent false
        }
        val target = DesktopFocusOrderPolicy.chipArrowTarget(index, key, requesters.size)
            ?: return@onPreviewKeyEvent false
        runCatching { requesters[target].requestFocus() }.isSuccess
    }
