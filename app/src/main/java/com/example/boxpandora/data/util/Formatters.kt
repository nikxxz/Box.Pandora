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
        if (timestamp == null || timestamp <= 0L) return DateParts("", "--", "")
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000 else timestamp
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

    fun formatHeaderDate(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "Unknown"
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000 else timestamp
        
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = millis }
        
        return when {
            isSameDay(now, date) -> "Today"
            isYesterday(now, date) -> "Yesterday"
            now.get(Calendar.YEAR) == date.get(Calendar.YEAR) -> {
                SimpleDateFormat("MMMM d", Locale.US).format(date.time)
            }
            else -> {
                SimpleDateFormat("MMMM d, yyyy", Locale.US).format(date.time)
            }
        }
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, date: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply { 
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(yesterday, date)
    }
}
