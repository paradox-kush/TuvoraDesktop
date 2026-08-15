package com.nuvio.app.features.iptv

/**
 * Catch-up (`tv_archive`) replay: which URL to ask a panel for, and whether a programme is old
 * enough to have been recorded but young enough to still be there.
 *
 * Pure so the dialects and the window can be pinned in tests — a wrong catch-up URL is a dead
 * channel rather than a degraded one, and the only way to find out against a real panel is to try
 * it on someone's live subscription.
 *
 * KMP twin of NuvioTV's XtreamCatchUp. commonMain has no timezone database, so `start` is
 * formatted with pure epoch arithmetic plus the panel's MEASURED clock-pair offset
 * ([ServerClockOffset]); the IANA-timezone-name fallback stays TV-only.
 */
object XtreamCatchUp {

    /**
     * Panels interpret `start` in THEIR OWN timezone, so a panel in New York replaying a programme
     * we describe in UTC lands hours off. [serverOffsetMs] is the measured clock-pair offset
     * ([ServerClockOffset]): panel-local wall time is the UTC instant plus the offset, formatted
     * here with the Hinnant civil-from-days algorithm — no SimpleDateFormat, no TimeZone.
     *
     * The fallback is UTC rather than the device's local time. Most panels never report a usable
     * zone, and UTC is what Tuvora has always sent — moving the default would silently shift
     * replay for every provider that works today.
     */
    fun formatStart(startMs: Long, serverOffsetMs: Long? = null): String {
        val shiftedMs = startMs + (serverOffsetMs ?: 0L)
        val totalSecs = shiftedMs.floorDiv(1_000L)
        val days = totalSecs.floorDiv(86_400L)
        val secsOfDay = totalSecs.mod(86_400L)
        // Hinnant civil_from_days
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        val hh = secsOfDay / 3_600L
        val mm = (secsOfDay % 3_600L) / 60L
        fun pad(n: Long) = n.toString().padStart(2, '0')
        return "${year.toString().padStart(4, '0')}-${pad(m)}-${pad(d)}:${pad(hh)}-${pad(mm)}"
    }

    /** What the guide can offer for one programme. */
    enum class ProgrammeAction {
        /** Nothing playable: not broadcast yet, or gone from the panel. */
        NONE,

        /** Airing now on a channel with no archive — ordinary live playback. */
        PLAY_LIVE,

        /** Airing now, and the panel kept the beginning: restart from the top. */
        START_OVER,

        /** Finished, and still inside the panel's window. */
        REPLAY,
    }

    /**
     * Which action a guide cell should offer.
     *
     * Deliberately covers the airing programme as well as finished ones: a channel with an archive
     * can restart what is on right now, which is the catch-up affordance most viewers actually
     * reach for, and a channel WITHOUT an archive must still be watchable live rather than
     * offering nothing at all.
     *
     * [programmeHasArchive] is the per-programme `has_archive` flag from get_simple_data_table —
     * the panel saying, recording by recording, what it actually kept, which is the strongest
     * signal there is. POSITIVE override only: true makes the programme replayable past every
     * channel-level rule (the start must still have passed); false and null leave the channel
     * rules untouched, because many panels serve catch-up while never marking a single row.
     */
    fun actionFor(
        programmeStartMs: Long,
        programmeEndMs: Long,
        nowMs: Long,
        hasArchive: Boolean,
        catchUpDays: Int,
        programmeHasArchive: Boolean? = null,
    ): ProgrammeAction {
        // Degenerate EPG rows: a zero/negative-length programme, or a zero/epoch start (an absent
        // timestamp parses to 0). No real broadcast looks like this, and a replay URL built from
        // it is guaranteed dead — refuse before any other rule can offer one.
        if (programmeEndMs <= programmeStartMs || programmeStartMs <= 0) return ProgrammeAction.NONE
        if (programmeStartMs > nowMs) return ProgrammeAction.NONE
        val replayable = programmeHasArchive == true ||
            (hasArchive && isWithinWindow(programmeStartMs, nowMs, catchUpDays))
        val finished = programmeEndMs <= nowMs
        return when {
            finished && replayable -> ProgrammeAction.REPLAY
            finished -> ProgrammeAction.NONE
            replayable -> ProgrammeAction.START_OVER
            else -> ProgrammeAction.PLAY_LIVE
        }
    }

    /** Whole minutes, floored, never below one — a zero-length request plays nothing. */
    fun durationMinutes(startMs: Long, endMs: Long): Int =
        (((endMs - startMs) / 60_000L).toInt()).coerceAtLeast(1)

    /**
     * Whether a programme can still be replayed.
     *
     * [catchUpDays] comes from the panel's `tv_archive_duration`, which is frequently absent or
     * zero even on providers that do serve catch-up — `tv_archive` is the real flag. So an unknown
     * window permits replay rather than hiding a feature the provider supports; the panel's own
     * error is a better answer than a missing button. A programme that has not aired yet is never
     * replayable, whatever the window says.
     */
    fun isWithinWindow(programmeStartMs: Long, nowMs: Long, catchUpDays: Int): Boolean {
        if (programmeStartMs > nowMs) return false
        if (catchUpDays <= 0) return true
        return nowMs - programmeStartMs <= catchUpDays * 24L * 60 * 60 * 1000
    }

    /**
     * Every catch-up URL worth trying, best-known first.
     *
     * Panels do not agree on the shape and none of them advertise which they speak, so the caller
     * walks this list until one plays. The first entry is the XUI path form Tuvora already shipped
     * and must stay exactly that, or panels that work today would regress.
     */
    fun candidateUrls(
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int,
        startMs: Long,
        endMs: Long,
        containerExtension: String?,
        serverOffsetMs: Long? = null,
    ): List<String> {
        // Blank credentials cannot form a playable URL — built anyway they become
        // `.../timeshift///60/...`, a failure that looks like a provider fault.
        if (username.isBlank() || password.isBlank()) return emptyList()
        val root = baseUrl.trimEnd('/')
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "ts"
        val start = formatStart(startMs, serverOffsetMs)
        val minutes = durationMinutes(startMs, endMs)
        val u = encode(username)
        val p = encode(password)
        val startPath = encode(start)
        val query = "username=$u&password=$p&stream=$streamId&start=$startPath&duration=$minutes"

        return listOf(
            // XUI standard, and what we already ship.
            "$root/timeshift/$u/$p/$minutes/$startPath/$streamId.$ext",
            // Same idea, id and start swapped — a common panel variant.
            "$root/timeshifts/$u/$p/$minutes/$streamId/$startPath.$ext",
            "$root/streaming/timeshift.php?$query&extension=$ext",
            "$root/streaming/timeshift.php?$query",
            "$root/timeshift.php?$query",
        ).distinct()
    }

    /**
     * Percent-encode a path/query segment. Credentials routinely contain characters that would
     * otherwise change the URL's shape — a `/` in a password silently adds a path segment.
     */
    private fun encode(value: String): String = buildString {
        value.forEach { c ->
            if (c.isLetterOrDigit() || c in UNRESERVED) append(c)
            else c.toString().encodeToByteArray().forEach { b ->
                val v = b.toInt() and 0xFF
                append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
        }
    }

    private const val UNRESERVED = "-_.~:"
    private const val HEX = "0123456789ABCDEF"
}
