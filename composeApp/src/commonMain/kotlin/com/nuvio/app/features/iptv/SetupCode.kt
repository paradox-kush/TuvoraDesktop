package com.nuvio.app.features.iptv

/** Why typed text cannot be a setup code. Decided on the device, before any request (no rate-limit strike). */
enum class SetupCodeProblem { EMPTY, BAD_CHARACTERS, WRONG_LENGTH }

/** The result of [SetupCode.normalize]: the 12 code characters, or why the text is not a code. */
sealed interface SetupCodeParse {
    data class Valid(val code: String) : SetupCodeParse
    data class Invalid(val problem: SetupCodeProblem) : SetupCodeParse
}

/**
 * Setup codes (`TUV-ABCD-EFGH-JKMN`) — what a provider hands a customer. Mirror of nuvio-web
 * `src/lib/providers/code.ts`, which holds the golden vectors this file's test shares. The server
 * stores only sha256 of the normalized form, so every platform MUST normalize identically: uppercase,
 * drop whitespace and dashes, drop the `TUV` prefix, refuse any character outside [ALPHABET].
 *
 * A setup code is NEVER persisted to disk, logged, put in analytics, crash breadcrumbs or any saved
 * navigation argument: it lives in [SetupCodeHolder] (memory only) and is cleared on success, on Cancel,
 * and 30 minutes after being set.
 */
object SetupCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 12
    const val PREFIX = "TUV"

    private fun squash(input: String): String =
        input.uppercase().filterNot { it.isWhitespace() || it == '-' }

    /**
     * The prefix is only dropped when what is left is exactly a code, so a bare 12-character code that
     * happens to START with "TUV" survives.
     */
    fun normalize(input: String): SetupCodeParse {
        var s = squash(input)
        if (s.isEmpty()) return SetupCodeParse.Invalid(SetupCodeProblem.EMPTY)
        if (s.length == LENGTH + PREFIX.length && s.startsWith(PREFIX)) s = s.drop(PREFIX.length)
        if (s.any { it !in ALPHABET }) return SetupCodeParse.Invalid(SetupCodeProblem.BAD_CHARACTERS)
        if (s.length != LENGTH) return SetupCodeParse.Invalid(SetupCodeProblem.WRONG_LENGTH)
        return SetupCodeParse.Valid(s)
    }

    /** The normalized code, or null. */
    fun parse(input: String): String? = (normalize(input) as? SetupCodeParse.Valid)?.code

    /** `TUV-ABCD-EFGH-JKMN` for a valid code; [input] unchanged otherwise. */
    fun format(input: String): String {
        val code = parse(input) ?: return input
        return (listOf(PREFIX) + code.chunked(4)).joinToString("-")
    }

    /**
     * The entry field's text while typing: the characters typed so far, uppercase, grouped as
     * `TUV-XXXX-XXXX-XXXX`, with the `TUV-` lead shown as soon as anything is typed. A leading `TUV`
     * followed by a separator ("TUV-AB…", "tuv ab…") or making a 15-character run is the prefix and is
     * not doubled; a bare "TUVW…" is code characters. Characters outside [ALPHABET] are KEPT (so the
     * person sees what they typed and [normalize] names the problem), and the field never holds more
     * than [LENGTH] code characters.
     */
    fun liveFormat(typing: String): String {
        val upper = typing.uppercase().trimStart()
        var s = squash(upper)
        val hasSeparatedPrefix = upper.startsWith(PREFIX) && upper.length > PREFIX.length &&
            (upper[PREFIX.length].isWhitespace() || upper[PREFIX.length] == '-')
        if (hasSeparatedPrefix || (s.length == LENGTH + PREFIX.length && s.startsWith(PREFIX))) s = s.drop(PREFIX.length)
        s = s.take(LENGTH)
        if (s.isEmpty()) return ""
        return (listOf(PREFIX) + s.chunked(4)).joinToString("-")
    }

    /** True when [typing] is a complete, valid code. */
    fun isComplete(typing: String): Boolean = parse(typing) != null

    /**
     * A code inside a pasted link such as `https://tuvora.co/s/TUV-ABCD-EFGH-JKMN` (or a message that
     * contains one); null when nothing in [text] is a code. Used by Paste and by the deep link.
     */
    fun extractFromLink(text: String): String? {
        val marker = text.indexOf("/s/", ignoreCase = true)
        if (marker < 0) return null
        val tail = text.substring(marker + 3).takeWhile { !it.isWhitespace() && it != '?' && it != '#' && it != '/' }
        return parse(tail)
    }
}
