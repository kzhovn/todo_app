package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.createdAt

// Waiting items (TaskType.WAITING): something you're blocked on but don't do yourself. One can be a
// prerequisite like any task; it never shows in Doing or Active. Every few days (checkInDays) it comes
// up in a quiet Waiting section under Doing, until it's resolved (completed). One with a date (its
// dueDate, "Resolves on") never comes up: it resolves itself that day.

const val DEFAULT_CHECK_IN_DAYS = 3
val CHECK_IN_CHOICES = listOf(3 to "Every 3 days", 7 to "Weekly", 14 to "Every 2 weeks", 30 to "Monthly")
private const val DAY = 24L * 60 * 60 * 1000

fun Task.checkInEvery(): Int = checkInDays ?: DEFAULT_CHECK_IN_DAYS

// The first check-in comes an interval after it was made; "Still waiting" moves startDate on.
fun nextCheckIn(task: Task): Long = task.startDate ?: (createdAt(task) ?: 0L) + task.checkInEvery() * DAY

// Open, undated waiting items whose check-in has come round, longest waiting first.
fun waitingToCheck(all: List<Task>, now: Long): List<Task> =
    all.filter { it.type == TaskType.WAITING && !it.isComplete && it.dueDate == null && nextCheckIn(it) <= now }
        .sortedBy { createdAt(it) ?: 0L }

// "Still waiting": hidden until the next check-in.
fun stillWaiting(task: Task, now: Long): Task = task.copy(startDate = now + task.checkInEvery() * DAY)

// A dated waiting item whose day has come: it resolves itself (see purgeExpired on each side).
fun Task.resolvesBy(now: Long): Boolean = type == TaskType.WAITING && !isComplete && dueDate != null && dueDate <= now

// "9 days", for how long it's been waiting (nothing on its first day).
fun waitingFor(task: Task, now: Long): String? = createdAt(task)?.let { ((now - it) / DAY).toInt() }?.takeIf { it > 0 }?.let { if (it == 1) "1 day" else "$it days" }

// The editor's line: "Check-in due." or "Next check-in Oct 9.", then how long it's been waiting.
fun checkInText(task: Task, now: Long): String = listOfNotNull(
    nextCheckIn(task).let { if (it <= now) "Check-in due." else "Next check-in ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(it))}." },
    waitingFor(task, now)?.let { "Waiting $it." }
).joinToString(" ")
