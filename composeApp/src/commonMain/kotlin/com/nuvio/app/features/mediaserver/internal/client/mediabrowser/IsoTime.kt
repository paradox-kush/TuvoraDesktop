package com.nuvio.app.features.mediaserver.internal.client.mediabrowser

/** Epoch milliseconds as an ISO-8601 UTC instant (`2026-10-06T12:00:00Z`) - the form `NextUpDateCutoff` takes. Pure. */
internal object IsoTime {
    fun format(epochMs: Long): String {
        val totalSeconds = epochMs.floorDiv(1000L)
        val days = totalSeconds.floorDiv(86_400L)
        val secondsOfDay = totalSeconds.mod(86_400L).toInt()
        // civil_from_days (Howard Hinnant)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = (if (m <= 2) y + 1 else y).toInt()
        val hh = secondsOfDay / 3600
        val mm = secondsOfDay % 3600 / 60
        val ss = secondsOfDay % 60
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(year, 4)}-${p(m)}-${p(d)}T${p(hh)}:${p(mm)}:${p(ss)}Z"
    }
}
