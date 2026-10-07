package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.quickadd.startOfDay
import java.util.Calendar

// The quick date choices offered on every device: snoozing (or a quick start) and a quick due day.

// In an hour, tomorrow at the day rollover (4am by default, not 24 hours from now), or a week from now.
enum class SnoozeChoice(val label: String) {
    HOUR(Labels.SNOOZE_HOUR), TOMORROW(Labels.SNOOZE_TOMORROW), WEEK(Labels.SNOOZE_WEEK);

    fun at(now: Long, rolloverHour: Int): Long = when (this) {
        HOUR -> now + 60 * 60 * 1000L
        TOMORROW -> nextRollover(now, rolloverHour)
        WEEK -> now + 7 * 24 * 60 * 60 * 1000L
    }
}

// Due dates are days: today, tomorrow, a week from today (no time).
enum class DueChoice(val label: String, private val days: Int) {
    TODAY("Today", 0), TOMORROW(Labels.SNOOZE_TOMORROW, 1), NEXT_WEEK("Next week", 7);

    fun at(now: Long): Long = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, days) }.startOfDay()
}
