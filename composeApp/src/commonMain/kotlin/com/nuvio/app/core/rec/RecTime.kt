package com.nuvio.app.core.rec

private const val MILLIS_PER_DAY = 86_400_000L

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
}

/**
 * Epoch millis as a UTC ISO-8601 timestamp, e.g. `2026-08-04T09:15:03.412Z`.
 *
 * Hand-rolled rather than pulled from a date library: this is the only formatting the whole
 * package needs, the backend parses it with `Date.parse`, and adding kotlinx-datetime to
 * commonMain for one function would be a poor trade. Uses Howard Hinnant's civil-from-days,
 * which is exact for all dates the app can produce.
 */
internal fun recIsoTimestamp(epochMillis: Long): String {
    // Floor division, not truncating division: Kotlin's `/` rounds toward zero, which would put
    // any pre-1970 instant on the wrong day. (Math.floorDiv is JVM-only, so it is spelled out.)
    val days = floorDiv(epochMillis, MILLIS_PER_DAY)
    val millisOfDay = epochMillis - days * MILLIS_PER_DAY

    // civil_from_days: shift the era so March is month 1 and the leap day lands last.
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val calendarYear = if (month <= 2) year + 1 else year

    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay / 60_000L) % 60).toInt()
    val second = ((millisOfDay / 1_000L) % 60).toInt()
    val milli = (millisOfDay % 1_000L).toInt()

    return buildString(24) {
        append(calendarYear.toString().padStart(4, '0')); append('-')
        append(month.toString().padStart(2, '0')); append('-')
        append(day.toString().padStart(2, '0')); append('T')
        append(hour.toString().padStart(2, '0')); append(':')
        append(minute.toString().padStart(2, '0')); append(':')
        append(second.toString().padStart(2, '0')); append('.')
        append(milli.toString().padStart(3, '0')); append('Z')
    }
}
