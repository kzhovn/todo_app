package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.DAY_MS
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

private const val DOING_DUE_SOON_MILLIS = 2 * DAY_MS

// Shared by the app's lists, the widget, focus mode and the server, so "Doing" means the same
// thing everywhere.
// Callers pass an effectiveDueDate resolver so a subtask that only inherits its due date from an
// ancestor still counts as due soon — matching what the task row actually displays.
fun filterDoing(
    tasks: List<Task>,
    now: Long,
    effectiveDueDate: (Task) -> Long? = { it.dueDate }
): List<Task> {
    val due = tasks.associate { it.id to effectiveDueDate(it) } // looked up once per task
    return urgentFirst(tasks.filter { task -> task.isStarred || due[task.id]?.let { it - now < DOING_DUE_SOON_MILLIS } ?: false || isStale(task, now) }, now) { due[it.id] }
}

// What's overdue or due today goes first, soonest first (so overdue leads), with repeats gone stale
// (RecurrenceEngine.staleAt) among them, stalest first; then high priority ("!"); the rest keep their order.
fun urgentFirst(tasks: List<Task>, now: Long, effectiveDueDate: (Task) -> Long?): List<Task> {
    val due = tasks.associate { it.id to effectiveDueDate(it) }
    val (urgent, rest) = tasks.partition { t -> due[t.id]?.let { dueStatus(it, now) != DueStatus.LATER } == true || isStale(t, now) }
    val (high, normal) = rest.partition { it.isHighPriority }
    return urgent.sortedBy { due[it.id] ?: RecurrenceEngine.staleAt(it) } + high + normal
}

private fun isStale(task: Task, now: Long) = RecurrenceEngine.staleAt(task)?.let { now >= it } == true

// The same, by the due date each task shows (its own, an ancestor's, or a waiting task's).
fun filterDoing(tasks: List<Task>, now: Long, dues: DueDates): List<Task> = filterDoing(tasks, now, dues::at)

fun urgentFirst(tasks: List<Task>, now: Long, dues: DueDates): List<Task> = urgentFirst(tasks, now, dues::at)
