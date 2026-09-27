package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.dueStatus

private const val DOING_DUE_SOON_MILLIS = 2 * 24 * 60 * 60 * 1000L

// Shared by TaskListViewModel (in-app Doing tab) and TodoWidget so "Doing" means the same
// thing everywhere, instead of two copies of this rule drifting apart.
// Callers pass an effectiveDueDate resolver so a subtask that only inherits its due date from an
// ancestor still counts as due soon — matching what the task row actually displays.
fun filterDoing(
    tasks: List<Task>,
    now: Long,
    effectiveDueDate: (Task) -> Long? = { it.dueDate }
): List<Task> = urgentFirst(
    tasks.filter { task -> task.isStarred || effectiveDueDate(task)?.let { it - now < DOING_DUE_SOON_MILLIS } ?: false },
    now,
    effectiveDueDate
)

// What's overdue or due today goes first, soonest first (so overdue leads); the rest keep their order.
fun urgentFirst(tasks: List<Task>, now: Long, effectiveDueDate: (Task) -> Long?): List<Task> {
    val (urgent, rest) = tasks.partition { t -> effectiveDueDate(t)?.let { dueStatus(it, now) != DueStatus.LATER } == true }
    return urgent.sortedBy { effectiveDueDate(it) } + rest
}

// The same, by the due date each task shows (its own, or inherited from an ancestor).
fun filterDoing(tasks: List<Task>, now: Long, byId: Map<Long, Task>, contextsByTaskId: Map<Long, Set<Long>>): List<Task> =
    filterDoing(tasks, now) { resolveEffective(it, byId, contextsByTaskId).effectiveDueDate }

fun urgentFirst(tasks: List<Task>, now: Long, byId: Map<Long, Task>, contextsByTaskId: Map<Long, Set<Long>>): List<Task> =
    urgentFirst(tasks, now) { resolveEffective(it, byId, contextsByTaskId).effectiveDueDate }
