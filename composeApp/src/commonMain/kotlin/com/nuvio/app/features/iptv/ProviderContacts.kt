package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/** The contact kinds a provider can publish, in the order the buttons are shown. */
enum class ContactKind { TELEGRAM, WHATSAPP, EMAIL, WEBSITE }

/** One tappable contact: [url] is what the platform opens, [text] what is shown beside/under it. */
data class ContactLink(val kind: ContactKind, val text: String, val url: String)

/**
 * A provider's support contacts exactly as the server stores them (already normalized there: digits for
 * WhatsApp, a bare handle for Telegram, a plain address for email, an http(s) address for the website).
 * Everything here is defensive anyway — a contact becomes a link only when it passes the same shape
 * rules as nuvio-web `src/lib/providers/support.ts`, and only contacts the provider set are shown.
 */
@Serializable
data class ProviderSupport(
    val whatsapp: String? = null,
    val telegram: String? = null,
    val email: String? = null,
    val website: String? = null,
) {
    /** The contacts that can be opened, Telegram first (the round buttons' order in the design). */
    fun links(): List<ContactLink> = buildList {
        ProviderContacts.telegram(telegram)?.let { add(ContactLink(ContactKind.TELEGRAM, "@$it", "https://t.me/$it")) }
        ProviderContacts.whatsapp(whatsapp)?.let { add(ContactLink(ContactKind.WHATSAPP, "+$it", "https://wa.me/$it")) }
        ProviderContacts.email(email)?.let { add(ContactLink(ContactKind.EMAIL, it, "mailto:$it")) }
        ProviderContacts.website(website)?.let { add(ContactLink(ContactKind.WEBSITE, ProviderContacts.websiteHost(it), it)) }
    }

    val isEmpty: Boolean get() = links().isEmpty()

    companion object {
        val NONE = ProviderSupport()
    }
}

/** Shape rules for one contact value; each returns the normalized value or null (never throws). */
object ProviderContacts {
    private val emailShape = Regex("""^[A-Za-z0-9.!#$'*+/=_~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$""")
    private val telegramShape = Regex("""^[A-Za-z0-9_]{5,32}$""")

    /** Digits only (with country code), 7-15 long. "+44 7700 900123" -> "447700900123". */
    fun whatsapp(value: String?): String? {
        val digits = value.orEmpty().filter { it in '0'..'9' }
        return digits.takeIf { it.length in 7..15 }
    }

    /** A Telegram username (5-32 letters/digits/underscores), with or without @ or a t.me link. */
    fun telegram(value: String?): String? {
        val s = value.orEmpty().trim()
            .replace(Regex("^https?://(www\\.)?t\\.me/", RegexOption.IGNORE_CASE), "")
            .removePrefix("@")
        return s.takeIf { telegramShape.matches(it) }
    }

    /** A plain address only: the mailto link is built from it, so no ? & % quotes or brackets. */
    fun email(value: String?): String? {
        val s = value.orEmpty().trim()
        return s.takeIf { it.length <= 254 && emailShape.matches(it) }
    }

    /**
     * An http(s) address with a host, no spaces/control characters and no userinfo. The server applies
     * the full public-address rules when the provider saves it; this only refuses what must never be
     * handed to the platform's link opener.
     */
    fun website(value: String?): String? {
        val s = value.orEmpty().trim()
        if (s.isEmpty() || s.length > 2048) return null
        if (s.any { it.code <= 0x20 || it.code in 0x7f..0x9f || it in "<>\"\\^`|" }) return null
        val scheme = when {
            s.startsWith("https://", ignoreCase = true) -> "https://"
            s.startsWith("http://", ignoreCase = true) -> "http://"
            else -> return null
        }
        val authority = s.substring(scheme.length).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || '@' in authority || '%' in authority) return null
        val host = authority.substringBefore(':')
        if (host.isEmpty() || '.' !in host) return null
        return s
    }

    fun websiteHost(url: String): String =
        url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
}
