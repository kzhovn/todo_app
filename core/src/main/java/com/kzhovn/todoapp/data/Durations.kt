package com.kzhovn.todoapp.data

// A timed task's length as shown on it: "45m", "1h", "1h 30m".
fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

// A running timer's time left: "18:42", or "1:05:00" from an hour up.
fun countdown(millis: Long): String {
    val s = (millis.coerceAtLeast(0) + 999) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
