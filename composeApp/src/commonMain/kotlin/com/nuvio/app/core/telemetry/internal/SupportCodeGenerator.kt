package com.nuvio.app.core.telemetry.internal

/**
 * The per-install support code: 8 characters from an alphabet with no look-alikes (no 0/O/1/I/L),
 * shown as `K7Q2-9MPX`. Pure: the random source is passed in so the host supplies a secure one.
 */
internal object SupportCodeGenerator {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 8

    fun generate(randomInt: (bound: Int) -> Int): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[randomInt(ALPHABET.length)]) } }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    fun display(code: String): String =
        if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code

    /**
     * Lookup form for support: tolerant of case, a missing dash or spaces, and of O/0 and I/1/L typos.
     * Apply to both sides of a comparison (the stored code never contains those characters).
     */
    fun lookupKey(input: String): String = buildString {
        for (c in input.uppercase()) {
            when {
                c == '-' || c == ' ' -> Unit
                c == 'O' -> append('0')
                c == 'I' || c == 'L' -> append('1')
                else -> append(c)
            }
        }
    }
}
