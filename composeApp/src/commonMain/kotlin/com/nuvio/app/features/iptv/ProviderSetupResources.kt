package com.nuvio.app.features.iptv

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_contact_email
import nuvio.composeapp.generated.resources.provider_contact_telegram
import nuvio.composeapp.generated.resources.provider_contact_website
import nuvio.composeapp.generated.resources.provider_contact_whatsapp
import nuvio.composeapp.generated.resources.provider_setup_msg_bad_characters
import nuvio.composeapp.generated.resources.provider_setup_msg_enter_code
import nuvio.composeapp.generated.resources.provider_setup_msg_expired
import nuvio.composeapp.generated.resources.provider_setup_msg_network
import nuvio.composeapp.generated.resources.provider_setup_msg_profile_gone
import nuvio.composeapp.generated.resources.provider_setup_msg_rate_limited
import nuvio.composeapp.generated.resources.provider_setup_msg_unusable
import nuvio.composeapp.generated.resources.provider_setup_msg_wrong_length
import nuvio.composeapp.generated.resources.provider_setup_source_m3u_url
import nuvio.composeapp.generated.resources.provider_setup_source_other
import nuvio.composeapp.generated.resources.provider_setup_source_stalker
import nuvio.composeapp.generated.resources.provider_setup_source_xtream
import org.jetbrains.compose.resources.StringResource

// Which string resource carries each approved sentence ([SetupCodeMessage.english] is the English text
// these resources hold). Pure mapping, no Compose: the screens resolve them with stringResource().

internal fun SetupCodeMessage.resource(): StringResource = when (this) {
    SetupCodeMessage.ENTER_CODE -> Res.string.provider_setup_msg_enter_code
    SetupCodeMessage.BAD_CHARACTERS -> Res.string.provider_setup_msg_bad_characters
    SetupCodeMessage.WRONG_LENGTH -> Res.string.provider_setup_msg_wrong_length
    SetupCodeMessage.EXPIRED -> Res.string.provider_setup_msg_expired
    SetupCodeMessage.RATE_LIMITED -> Res.string.provider_setup_msg_rate_limited
    SetupCodeMessage.NETWORK -> Res.string.provider_setup_msg_network
    SetupCodeMessage.UNUSABLE -> Res.string.provider_setup_msg_unusable
    SetupCodeMessage.PROFILE_GONE -> Res.string.provider_setup_msg_profile_gone
}

internal fun sourceTypeResource(sourceType: String): StringResource = when (sourceType) {
    SOURCE_TYPE_XTREAM -> Res.string.provider_setup_source_xtream
    SOURCE_TYPE_M3U_URL -> Res.string.provider_setup_source_m3u_url
    SOURCE_TYPE_STALKER -> Res.string.provider_setup_source_stalker
    else -> Res.string.provider_setup_source_other
}

internal fun ContactKind.resource(): StringResource = when (this) {
    ContactKind.TELEGRAM -> Res.string.provider_contact_telegram
    ContactKind.WHATSAPP -> Res.string.provider_contact_whatsapp
    ContactKind.EMAIL -> Res.string.provider_contact_email
    ContactKind.WEBSITE -> Res.string.provider_contact_website
}
