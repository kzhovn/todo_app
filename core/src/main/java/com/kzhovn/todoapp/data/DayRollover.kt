package com.kzhovn.todoapp.data

import java.time.LocalTime

const val DEFAULT_ROLLOVER_HOUR = 4

// The next time the "day" ends: rolloverHour:00 in the default timezone, strictly after now.
// A 4am rollover means a task added at 1am still lives until 4am that same morning.
fun nextRollover(now: Long, rolloverHour: Int = DEFAULT_ROLLOVER_HOUR): Long {
    val today = atTime(now, rolloverHour, 0)
    return if (today > now) today else localDay(now).plusDays(1).atTimeMillis(LocalTime.of(rolloverHour, 0))
}
