package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task

private const val DOING_DUE_SOON_MILLIS = 2 * 24 * 60 * 60 * 1000L

// Shared by TaskListViewModel (in-app Doing tab) and TodoWidget so "Doing" means the same
// thing everywhere, instead of two copies of this rule drifting apart.
fun filterDoing(tasks: List<Task>, now: Long): List<Task> =
    tasks.filter { it.isStarred || (it.dueDate != null && it.dueDate - now < DOING_DUE_SOON_MILLIS) }
