package com.kzhovn.todoapp.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// How a due date colours a row's checkbox and its "due …" text, in both apps.
enum class DueStatus { OVERDUE, TODAY, LATER }

fun dueStatus(due: Long, now: Long): DueStatus = when {
    deadline(due) <= now -> DueStatus.OVERDUE
    dayIndex(due) == dayIndex(now) -> DueStatus.TODAY
    else -> DueStatus.LATER
}

// A row's due text: "due today", "due 3:00 PM" (today, with a time), "due tomorrow",
// "due yesterday", "due Fri" (within the week ahead), else "due Oct 3".
fun dueText(due: Long, now: Long): String = "due " + dayText(due, now)

// A date as the editor's chips and pills show it: "Oct 6", or "Oct 6 5:00 PM" with a time.
fun chipDate(millis: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(millis)) + if (hasTime(millis)) " " + SimpleDateFormat("h:mm a", Locale.US).format(Date(millis)) else ""

// "starts Fri": a start date still to come, in the same words.
fun startText(start: Long, now: Long): String = "starts " + dayText(start, now)

private fun dayText(due: Long, now: Long): String {
    val days = dayIndex(due) - dayIndex(now)
    val date = Date(due)
    return when {
        days == 0L -> if (hasTime(due)) SimpleDateFormat("h:mm a", Locale.US).format(date) else "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days in 2..6 -> SimpleDateFormat("EEE", Locale.US).format(date)
        else -> SimpleDateFormat("MMM d", Locale.US).format(date)
    }
}

// Days since the epoch in local time, so calendar days compare across DST.
private fun dayIndex(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    (timeInMillis + get(Calendar.ZONE_OFFSET) + get(Calendar.DST_OFFSET)) / 86_400_000L
}
