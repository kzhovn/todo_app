package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.Task

data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false
)

object TodoWidgetPresenter {
    fun toRows(tasks: List<Task>, subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap(), now: Long = System.currentTimeMillis()): List<WidgetTaskRow> =
        tasks.map {
            WidgetTaskRow(it.id, it.title, it.isComplete, it.isStarred, it.isMaybe, subtaskCounts[it.id]?.takeIf { c -> c.second > 0 }, it.isBackburner(now))
        }
}
