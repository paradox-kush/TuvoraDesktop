package com.nuvio.app.features.auth

/**
 * "Did you mean …?" for sign-up email addresses. A faithful port of the backend's
 * `nuvio-backend/supabase/functions/_shared/signup/email_domain.ts` suggestion rules (same list,
 * rule order and thresholds), which the `auth-before-signup` hook uses when it refuses a domain
 * that can't receive mail. Running it here catches the typo before the round trip.
 *
 * Pure: no I/O, no platform code.
 */
object EmailTypoSuggestion {
    val POPULAR_DOMAINS: List<String> = listOf(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "icloud.com", "aol.com", "live.com", "msn.com",
        "me.com", "mac.com", "googlemail.com", "ymail.com", "protonmail.com", "proton.me", "gmx.com", "mail.com",
        "yandex.com", "rediffmail.com", "yahoo.co.uk", "yahoo.co.in", "hotmail.co.uk", "outlook.in", "live.co.uk",
    )

    /** A popular provider the domain is probably a typo of, or null. */
    fun suggestDomain(input: String): String? {
        val domain = input.trim().lowercase()
        if (domain in POPULAR_DOMAINS) return null

        val base = domain.split(".")[0]
        // Right provider, wrong or missing ending: gmail.con, gmail.coma, gmail
        val byBase = POPULAR_DOMAINS.firstOrNull { it.split(".")[0] == base && it.endsWith(".com") }
        if (byBase != null) return byBase

        // Leading letters dropped: ail.com -> gmail.com
        if (domain.length >= 6) {
            val bySuffix = POPULAR_DOMAINS.firstOrNull {
                it != domain && it.endsWith(domain) && it.length - domain.length <= 2
            }
            if (bySuffix != null) return bySuffix
        }

        // Small misspelling: gmial.com, hotmali.com, yahooo.com
        var best: String? = null
        var bestDistance = 3
        for (p in POPULAR_DOMAINS) {
            val d = levenshtein(domain, p)
            if (d > 0 && d < bestDistance) {
                best = p
                bestDistance = d
            }
        }
        return if (bestDistance <= 2 && domain.length >= 5) best else null
    }

    /** The whole address with its domain corrected (local part kept as typed), or null. */
    fun suggestEmail(email: String): String? {
        val trimmed = email.trim()
        val at = trimmed.lastIndexOf('@')
        if (at < 1) return null
        val fixed = suggestDomain(trimmed.substring(at + 1)) ?: return null
        return "${trimmed.substring(0, at)}@$fixed"
    }

    /** Edit distance; returns 3 early when the lengths differ by more than 2. */
    private fun levenshtein(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diag = prev[0]
            prev[0] = i
            for (j in 1..b.length) {
                val tmp = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diag + if (a[i - 1] == b[j - 1]) 0 else 1)
                diag = tmp
            }
        }
        return prev[b.length]
    }
}
