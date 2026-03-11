package com.example.boxpandora.data.util

import java.text.SimpleDateFormat
import java.util.*

object Formatters {
    fun formatCount(n: Int): String {
        return when {
            n < 1000 -> n.toString()
            n < 1_000_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
            else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    data class DateParts(val month: String, val day: String, val year: String)

    fun formatShortDateParts(timestamp: Long?): DateParts {
        // Treat null, 0, or dates near 1970 as invalid/dummy
        if (timestamp == null || timestamp <= 0L) return DateParts("", "--", "")
        
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000 else timestamp
        
        // Additional guard: If the date is before year 2000, it's likely a system dummy
        if (millis < 946684800000L) return DateParts("", "--", "")
        
        val date = Date(millis)
        val monthFormat = SimpleDateFormat("MMM", Locale.US)
        val dayFormat = SimpleDateFormat("dd", Locale.US)
        val yearFormat = SimpleDateFormat("yyyy", Locale.US)
        return DateParts(
            monthFormat.format(date),
            dayFormat.format(date),
            yearFormat.format(date)
        )
    }
}
