package com.nuvio.app.features.iptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.ExternalLinkPolicy
import com.nuvio.app.core.ui.rememberSafeUriOpener
import org.jetbrains.compose.resources.stringResource

@Composable
private fun ContactKind.label(): String = stringResource(resource())

private fun ContactKind.icon(): ImageVector = when (this) {
    ContactKind.TELEGRAM -> Icons.AutoMirrored.Rounded.Send
    ContactKind.WHATSAPP -> Icons.Rounded.Chat
    ContactKind.EMAIL -> Icons.Rounded.Email
    ContactKind.WEBSITE -> Icons.Rounded.Language
}

/**
 * Round contact buttons for the contacts a provider set (only those). A tap opens the platform's own
 * Telegram / WhatsApp / mail / browser through the app's safe link opener (which copies the link when
 * nothing can open it).
 */
@Composable
internal fun ProviderContactButtons(contacts: List<ContactLink>, modifier: Modifier = Modifier) {
    if (contacts.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val open = rememberSafeUriOpener()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10), verticalAlignment = Alignment.CenterVertically) {
        contacts.forEach { contact ->
            val description = contact.kind.label()
            Surface(
                modifier = Modifier.size(44.dp).clickable(role = Role.Button, onClickLabel = description) { if (ExternalLinkPolicy.isSafeContactLink(contact.url)) open(contact.url) },
                shape = CircleShape,
                color = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = contact.kind.icon(), contentDescription = description, tint = tokens.colors.accent, modifier = Modifier.size(tokens.icons.md))
                }
            }
        }
    }
}
