package com.nuvio.app.features.iptv

/** The two actions that take a playlist away from a profile's list, and the word that confirms each. */
enum class DestructiveAction(val word: String) {
    /** Keep the playlist, stop the provider updating it. */
    DETACH("DETACH"),
    /** Delete the playlist from this profile on every device. */
    REMOVE("REMOVE"),
}

/** The dialog sentences for a [DestructiveAction]; [extra] is the second paragraph when there is one. */
data class DestructiveConfirmCopy(val title: String, val message: String, val extra: String? = null)

/**
 * Type-to-confirm for Detach and for Remove playlist, on every playlist (owner request 2026-10-02: a
 * stray tap, click or button press can never detach or delete a playlist).
 *
 * A FIXED word, not the playlist's name: names can be long, URLs or non-Latin, and typing them invites
 * false mismatches. Compared after trimming, ignoring case and accents. Phone and Desktop type the word
 * (the confirm button and the Return key do nothing until [isConfirmed]); TV and Apple TV hold OK
 * instead ([HoldToConfirmPolicy]).
 */
object DestructiveConfirmPolicy {
    fun requiredWord(action: DestructiveAction): String = action.word

    fun isConfirmed(action: DestructiveAction, typed: String): Boolean =
        fold(typed) == requiredWord(action)

    /** Whether Return/Enter in the field may confirm: only when the typed word already matches. */
    fun returnConfirms(action: DestructiveAction, typed: String): Boolean = isConfirmed(action, typed)

    /**
     * trim -> Unicode NFKD -> drop combining marks -> uppercase, as a pure table so it runs the same on
     * every runner (common code has no normalizer). Covers what NFKD decomposes into a base Latin letter:
     * the Latin-1 / Latin Extended-A accented letters, fullwidth forms, and stray combining marks
     * (U+0300-U+036F). Letters NFKD does not decompose (stroked Đ Ø Ł Ħ Ŧ) are deliberately NOT folded.
     */
    fun fold(input: String): String {
        val out = StringBuilder()
        for (ch in input.trim()) {
            val code = ch.code
            when {
                code in 0x0300..0x036F -> Unit
                code in 0xFF01..0xFF5E -> out.append((code - 0xFEE0).toChar())
                else -> out.append(ch)
            }
        }
        val upper = out.toString().uppercase()
        val folded = StringBuilder(upper.length)
        for (ch in upper) folded.append(ACCENT_BASE[ch] ?: ch)
        return folded.toString().uppercase()
    }

    private val ACCENT_BASE: Map<Char, Char> = buildMap {
        fun group(base: Char, letters: String) = letters.forEach { put(it, base) }
        group('A', "ÀÁÂÃÄÅĀĂĄǍ")
        group('C', "ÇĆĈĊČ")
        group('D', "Ď")
        group('E', "ÈÉÊËĒĔĖĘĚ")
        group('G', "ĜĞĠĢ")
        group('H', "Ĥ")
        group('I', "ÌÍÎÏĨĪĬĮİǏ")
        group('J', "Ĵ")
        group('K', "Ķ")
        group('L', "ĹĻĽ")
        group('N', "ÑŃŅŇ")
        group('O', "ÒÓÔÕÖŌŎŐǑ")
        group('R', "ŔŖŘ")
        group('S', "ŚŜŞŠ")
        group('T', "ŢŤ")
        group('U', "ÙÚÛÜŨŪŬŮŰŲǓ")
        group('W', "Ŵ")
        group('Y', "ÝŶŸ")
        group('Z', "ŹŻŽ")
    }

    /** The dialog copy. [providerName] is required for Detach; [managed] adds Remove's re-add warning. */
    fun copy(
        action: DestructiveAction,
        playlistName: String,
        providerName: String? = null,
        managed: Boolean = false,
    ): DestructiveConfirmCopy = when (action) {
        DestructiveAction.DETACH -> {
            val provider = providerName?.takeIf { it.isNotBlank() } ?: "your provider"
            DestructiveConfirmCopy(
                title = "Detach from $provider?",
                message = "You keep this playlist, but $provider can't update it any more. " +
                    "If their server changes you would need to update it yourself.",
            )
        }
        DestructiveAction.REMOVE -> {
            val provider = providerName?.takeIf { it.isNotBlank() } ?: "your provider"
            DestructiveConfirmCopy(
                title = "Remove $playlistName?",
                message = "This deletes the playlist from this profile on all your devices.",
                extra = if (managed) "You may need a new code from $provider to get it back." else null,
            )
        }
    }
}

/**
 * Hold-to-confirm for TV and Apple TV (typing a word with a remote costs ~15 presses): hold OK for
 * [holdMs] on the destructive button with a filling ring. Releasing early, or a quick press, does
 * nothing. Pure so both TVs can run the same golden table: 0 -> 0.0, 1000 -> 0.5, 1999 -> ~0.9995 (not
 * confirmed), 2000 -> 1.0 (confirmed), 3000 -> 1.0.
 */
class HoldToConfirmPolicy(val holdMs: Long = DEFAULT_HOLD_MS) {
    fun progress(heldMs: Long): Float = (heldMs.toFloat() / holdMs.toFloat()).coerceIn(0f, 1f)

    fun isConfirmed(heldMs: Long): Boolean = heldMs >= holdMs

    companion object {
        const val DEFAULT_HOLD_MS = 2000L
    }
}
