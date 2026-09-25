package com.kzhovn.todoapp.data

import java.util.Calendar

// Start/due times are optional. A date without a time is stored as local midnight, so "has a time"
// means "isn't midnight".
// ponytail: a time of exactly 00:00 reads as date-only; add a flag column if that ever matters.
fun hasTime(epochMillis: Long): Boolean = Calendar.getInstance().apply { timeInMillis = epochMillis }.let {
    it.get(Calendar.HOUR_OF_DAY) != 0 || it.get(Calendar.MINUTE) != 0 || it.get(Calendar.SECOND) != 0
}

fun atTime(epochMillis: Long, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
    timeInMillis = epochMillis
    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

// When a due date actually passes: its time if it has one, otherwise the end of that day, so
// "due today" isn't overdue from the moment the day starts.
fun deadline(dueDate: Long): Long =
    if (hasTime(dueDate)) dueDate
    else Calendar.getInstance().apply { timeInMillis = dueDate; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
