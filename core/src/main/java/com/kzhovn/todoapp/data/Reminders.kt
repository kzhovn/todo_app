package com.kzhovn.todoapp.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A task's reminders, each optional: when it starts, some time before it's due, and one at any time.
enum class ReminderKind { START, DUE, AT }

const val DEFAULT_REMINDER_HOUR = 9

// When each reminder goes off. A date with no time counts from `hour` that day (the date-only reminder
// time in Settings): midnight is never a useful moment for a nudge.
fun reminderTimes(task: Task, hour: Int): Map<ReminderKind, Long> {
    fun at(ms: Long) = if (hasTime(ms)) ms else atTime(ms, hour, 0)
    return buildMap {
        if (task.remindAtStart) task.startDate?.let { put(ReminderKind.START, at(it)) }
        task.dueDate?.let { due -> task.reminderOffsetMinutes?.let { put(ReminderKind.DUE, at(due) - it * 60_000L) } }
        task.remindAt?.let { put(ReminderKind.AT, at(it)) }
    }
}

// The chip's and pill's text: "At start · 1h before due · Oct 6 3:00 PM"; null with none set.
fun reminderSummary(task: Task, hour: Int): String? = buildList {
    if (task.remindAtStart && task.startDate != null) add("At start")
    if (task.dueDate != null) task.reminderOffsetMinutes?.let { add(beforeDueText(it)) }
    task.remindAt?.let { add(reminderTimeText(if (hasTime(it)) it else atTime(it, hour, 0))) }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

fun reminderTimeText(ms: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(ms)) + " " + timeText(ms)

// A before-due reminder in short: "1h before due", "At due time" (the web's pill takes it from the option).
fun beforeDueText(minutes: Int): String = Labels.REMINDERS.firstOrNull { it.first == minutes }?.second?.let { label ->
    if (minutes == 0) label else label.replace(" min", "m").replace(" hour", "h").replace(" day", "d").replace("before", "before due")
} ?: "${minutes}m before due"
