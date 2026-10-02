package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.ExternalLinkPolicy
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberSafeUriOpener
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SetupCodeMessage.text(): String = stringResource(resource())

@Composable
internal fun SetupCodeProblem.text(): String = SetupCodeOutcome.fromProblem(this).message!!.text()

private fun ContactKind.icon(): ImageVector = when (this) {
    ContactKind.TELEGRAM -> Icons.AutoMirrored.Rounded.Send
    ContactKind.WHATSAPP -> Icons.Rounded.Chat
    ContactKind.EMAIL -> Icons.Rounded.Email
    ContactKind.WEBSITE -> Icons.Rounded.Language
}

/**
 * Round contact buttons, one per contact the provider set. A click opens the OS handler (Telegram, WhatsApp,
 * the mail client, the browser) — only for a link [ExternalLinkPolicy.isSafeContactLink] accepts, through the
 * app's safe opener (which copies the link when nothing can open it). The tooltip shows the handle so nothing
 * has to be opened to read it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopContactButtons(contacts: List<ContactLink>, modifier: Modifier = Modifier) {
    if (contacts.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val open = rememberSafeUriOpener()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        contacts.forEach { contact ->
            val label = stringResource(contact.kind.resource())
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("$label · ${contact.text}") } },
                state = rememberTooltipState(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected), CircleShape)
                        .clickable(role = Role.Button, onClickLabel = label) {
                            if (ExternalLinkPolicy.isSafeContactLink(contact.url)) open(contact.url)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(contact.kind.icon(), contentDescription = label, tint = tokens.colors.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A value tile in the details header (days left, connections, catalog counts). */
@Composable
internal fun StatTile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.lg))
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** The thin bar under "N days left": drawn only in the last 30 days (the caller passes a fraction only then). */
@Composable
internal fun ThinBar(fraction: Float, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium))) {
        Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(tokens.colors.accent))
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.Bold,
    )
}

/** A quiet tinted chip: the managed ribbon, a source-type tag. */
@Composable
internal fun RibbonChip(text: String, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .clip(tokens.shapes.chip)
            .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium, color = tokens.colors.accent, fontWeight = FontWeight.SemiBold)
    }
}
