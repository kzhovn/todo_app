package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.createdAt

// Waiting items (TaskType.WAITING): something you're blocked on but don't do yourself. One can be a
// prerequisite like any task; it never shows in Doing or Active. Every few days (checkInDays) it comes
// up in a quiet Waiting section under Doing, until it's resolved (completed). One with a date (its
// dueDate, "Resolves on") never comes up: it resolves itself that day.

const val DEFAULT_CHECK_IN_DAYS = 3
const val MAX_CHECK_IN_DAYS = 365
// The editor's one-tap intervals; any other number of days or weeks is typed in.
val CHECK_IN_PRESETS = listOf(3, 7, 14)

fun Task.checkInEvery(): Int = checkInDays ?: DEFAULT_CHECK_IN_DAYS

// A wait with a timer (the washing machine's 50 minutes): it resolves itself that long after it starts, which in
// a workflow is when the step before it is ticked (see Workflows.kt), and otherwise when it's made. Never checked in on.
val Task.isTimedWait: Boolean get() = type == TaskType.WAITING && durationMinutes != null

// Started at `from`: due when its timer's done, with a reminder then (the reminder can be cleared, for this run).
fun Task.startedWait(from: Long): Task = (from + durationMinutes!! * 60_000L).let { copy(dueDate = it, remindAt = it) }

// "+10 min" on a running wait (the Running line, its notification): later by that much, its reminder too.
fun Task.withMoreWait(minutes: Int, now: Long): Task {
    val end = maxOf(dueDate ?: now, now) + minutes * 60_000L
    return copy(dueDate = end, remindAt = if (remindAt != null) end else null)
}

// "32m left", "1h 10m left": a running wait's time left, to the next whole minute.
fun timeLeft(endsAt: Long, now: Long): String = formatDuration(((endsAt - now + 59_999) / 60_000).toInt().coerceAtLeast(1)) + " left"

// The first check-in comes an interval after it was made (an unsaved one's, from now); "Still waiting"
// and the editor move startDate.
fun nextCheckIn(task: Task, now: Long): Long = task.startDate ?: (createdAt(task) ?: now) + task.checkInEvery() * DAY_MS

// Open, undated waiting items whose check-in has come round, longest waiting first.
fun waitingToCheck(all: List<Task>, now: Long): List<Task> =
    all.filter { it.type == TaskType.WAITING && !it.isComplete && it.dueDate == null && !it.isTimedWait && nextCheckIn(it, now) <= now }
        .sortedBy { createdAt(it) ?: 0L }

// "Still waiting": hidden until the next check-in.
fun stillWaiting(task: Task, now: Long): Task = task.copy(startDate = now + task.checkInEvery() * DAY_MS)

// A new interval counts from now, so a shorter one brings the next check-in closer.
fun Task.withCheckInEvery(days: Int, now: Long): Task = stillWaiting(copy(checkInDays = days.coerceIn(1, MAX_CHECK_IN_DAYS)), now)

// A dated waiting item whose day has come: it resolves itself (see purgeExpired on each side).
fun Task.resolvesBy(now: Long): Boolean = type == TaskType.WAITING && !isComplete && dueDate != null && dueDate <= now

// "9 days", for how long it's been waiting (nothing on its first day).
fun waitingFor(task: Task, now: Long): String? = createdAt(task)?.let { ((now - it) / DAY_MS).toInt() }?.takeIf { it > 0 }?.let { if (it == 1) "1 day" else "$it days" }

// The editor's line: "Check-in due." or "Next check-in Oct 9.", then how long it's been waiting.
fun checkInText(task: Task, now: Long): String = listOfNotNull(
    nextCheckIn(task, now).let { if (it <= now) "Check-in due." else "${Labels.NEXT_CHECK_IN} ${monthDay(it)}." },
    waitingFor(task, now)?.let { "Waiting $it." }
).joinToString(" ")

// A waiting item's tail in its row: "resolves Oct 9", or "waiting 9 days"; a timed one's "32m left", or its
// length before it starts ("50m").
fun waitingTail(task: Task, now: Long): String? = when {
    task.isTimedWait && task.dueDate == null -> formatDuration(task.durationMinutes!!)
    task.isTimedWait && task.dueDate!! > now -> timeLeft(task.dueDate, now)
    else -> task.dueDate?.let { "resolves ${chipDate(it)}" } ?: waitingFor(task, now)?.let { "waiting $it" }
}

// The editor's line for one with a date, or a timed one still to start.
fun resolvesText(task: Task): String? = when {
    task.isTimedWait && task.dueDate == null -> "Starts when the step before it is ticked, and resolves itself ${formatDuration(task.durationMinutes!!)} later."
    task.isTimedWait -> "Resolves itself at ${chipDate(task.dueDate!!)}."
    else -> task.dueDate?.let { "Resolves itself on ${chipDate(it)}." }
}

// A list row's line after its title: a waiting item's tail, or a start still to come ("starts Fri").
fun laterText(task: Task, now: Long): String? = when {
    task.isComplete -> null
    task.type == TaskType.WAITING -> waitingTail(task, now)
    else -> task.startDate?.takeIf { it > now }?.let { startText(it, now) }
}

// How many open tasks each of these waiting items holds back.
fun waitingBlocks(waiting: List<Task>, edges: List<TaskDependency>, byId: Map<Long, Task>): Map<Long, Int> {
    val ids = waiting.map { it.id }.toSet()
    return edges.filter { it.dependsOnTaskId in ids && byId[it.taskId]?.isComplete == false }.groupingBy { it.dependsOnTaskId }.eachCount()
}
