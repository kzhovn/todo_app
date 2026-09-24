package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task

private const val DOING_DUE_SOON_MILLIS = 2 * 24 * 60 * 60 * 1000L

// Shared by TaskListViewModel (in-app Doing tab) and TodoWidget so "Doing" means the same
// thing everywhere, instead of two copies of this rule drifting apart.
// Callers pass an effectiveDueDate resolver so a subtask that only inherits its due date from an
// ancestor still counts as due soon — matching what the task row actually displays.
fun filterDoing(
    tasks: List<Task>,
    now: Long,
    effectiveDueDate: (Task) -> Long? = { it.dueDate }
): List<Task> =
    tasks.filter { task ->
        task.isStarred || effectiveDueDate(task)?.let { it - now < DOING_DUE_SOON_MILLIS } ?: false
    }
