package com.kzhovn.todoapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// One TIME context can hold several disjoint windows (e.g. 9-12 and 2-5), each independently
// restricted to a subset of weekdays via a bitmask (bit 0 = Sunday .. bit 6 = Saturday, matching
// java.util.Calendar.DAY_OF_WEEK - 1). windowEndMinute < windowStartMinute means the window
// spans midnight (e.g. 22:00-06:00).
@Entity(tableName = "context_time_windows")
data class ContextTimeWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contextId: Long,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val daysMask: Int = ALL_DAYS
) {
    companion object {
        const val ALL_DAYS = 0b1111111
    }
}
