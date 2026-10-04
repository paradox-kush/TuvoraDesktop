package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

// Sync-12 device pass (2026-10-04): the long "Always backdrop with logo for landscape posters" label
// pushed its switch past the card edge — the label had no weight, so it took the whole row.
class PosterToggleRowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longLabelWrapsAndTheSwitchStaysInsideTheRow() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.width(300.dp)) {
                    PosterToggleRow(
                        // Repeated so it must wrap whatever the test font metrics are.
                        title = "Always backdrop with logo for landscape posters ".repeat(8).trim(),
                        checked = true,
                        onCheckedChange = {},
                    )
                }
            }
        }
        val switchBounds = compose.onNode(isToggleable()).getUnclippedBoundsInRoot()
        val labelBounds = compose.onNodeWithText("Always backdrop", substring = true).getUnclippedBoundsInRoot()
        assertTrue(
            switchBounds.right - switchBounds.left >= 40.dp,
            "switch squeezed to ${switchBounds.right - switchBounds.left} by an unweighted label",
        )
        assertTrue(switchBounds.right <= 300.dp, "switch right edge ${switchBounds.right} is outside the 300dp row")
        assertTrue(
            labelBounds.right <= switchBounds.left,
            "label (right=${labelBounds.right}) runs under the switch (left=${switchBounds.left})",
        )
    }
}
