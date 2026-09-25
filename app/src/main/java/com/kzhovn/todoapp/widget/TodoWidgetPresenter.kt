package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType

data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false,
    val isSubtask: Boolean = false
)

object TodoWidgetPresenter {
    fun toRows(
        tasks: List<Task>,
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        allById: Map<Long, Task> = emptyMap()
    ): List<WidgetTaskRow> =
        tasks.map {
            val parentType = allById[it.parentId]?.type
            WidgetTaskRow(
                it.id, it.title, it.isComplete, it.isStarred, it.isMaybe, subtaskCounts[it.id]?.takeIf { c -> c.second > 0 },
                it.isBackburner(now), isSubtask = parentType == TaskType.TASK || parentType == TaskType.PROJECT
            )
        }
}
