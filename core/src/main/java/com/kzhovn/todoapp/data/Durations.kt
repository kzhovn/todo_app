package com.kzhovn.todoapp.data

// A timed task's length as shown on it: "45m", "1h", "1h 30m".
fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}
