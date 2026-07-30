package com.nuvio.app.features.radar

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val shortDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

internal actual object RadarTime {
    actual fun nowMs(): Long = System.currentTimeMillis()

    actual fun formatTime(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

    actual fun dayLabel(epochMs: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> Instant.ofEpochMilli(epochMs).atZone(zone).format(shortDate)
        }
    }
}
