package com.nuvio.app.features.iptv

/**
 * The desktop code field's decisions, pulled out of the composable so they test without Compose.
 * Mobile keeps its own entry in the Add Playlist page; this is the Desktop "Add a playlist" pane's.
 */
internal object SetupCodeEntryPolicy {
    /** Continue is enabled exactly when the field holds 12 valid characters. */
    fun canContinue(typed: String): Boolean = SetupCode.isComplete(typed)

    /**
     * The problem to show WHILE typing: a character outside the alphabet is wrong the moment it is typed; a short
     * code is not (it is just unfinished), and an empty field is not an error either.
     */
    fun liveProblem(typed: String): SetupCodeProblem? {
        val code = SetupCode.liveFormat(typed).removePrefix(SetupCode.PREFIX).filter { it != '-' }
        return if (code.any { it !in SetupCode.ALPHABET }) SetupCodeProblem.BAD_CHARACTERS else null
    }

    /**
     * A field edit that added several characters at once is a paste (or a drag-drop), not typing: a pasted code
     * goes straight to the preview, a typed one waits for Continue.
     */
    fun isPasteLike(previous: String, edited: String): Boolean = edited.length - previous.length >= 2

    /** A pasted/typed value that should go straight to the preview. */
    fun submitsOnPaste(typedAfterPaste: String): Boolean = canContinue(typedAfterPaste)
}
