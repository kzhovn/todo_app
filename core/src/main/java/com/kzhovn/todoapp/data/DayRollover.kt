package com.kzhovn.todoapp.data

import java.util.Calendar

const val DEFAULT_ROLLOVER_HOUR = 4

// The next time the "day" ends: rolloverHour:00 in the default timezone, strictly after now.
// A 4am rollover means a task added at 1am still lives until 4am that same morning.
fun nextRollover(now: Long, rolloverHour: Int = DEFAULT_ROLLOVER_HOUR): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, rolloverHour); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}
