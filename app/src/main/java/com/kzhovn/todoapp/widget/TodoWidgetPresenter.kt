package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.Task
import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.data.walkParentChain

data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false,
    val parentTitle: String? = null, // a subtask's parent, shown as "Parent: subtask"
    val parentId: Long? = null,
    val barColor: Color? = null, // the nearest folder's colour, as the app's rows show it
    val durationMinutes: Int? = null, // a timed task: the row gets a play button with its length
    val due: DueStatus? = null // colours the checkbox ring
)

object TodoWidgetPresenter {
    fun toRows(
        tasks: List<Task>,
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        allById: Map<Long, Task> = emptyMap(),
        folderColors: Map<Long, Color> = emptyMap()
    ): List<WidgetTaskRow> =
        // Grouped by top-level folder in the All tree's order (folderless last), like Active's sections.
        sectionsByTopFolder(tasks, allById).flatMap { it.second }.map {
            WidgetTaskRow(
                it.id, it.title, it.isComplete, it.isStarred, it.isMaybe, subtaskCounts[it.id]?.takeIf { c -> c.second > 0 },
                it.isBackburner(now), parentTitle = subtaskParentTitle(it, allById), parentId = it.parentId,
                barColor = it.parentId?.let { parent -> walkParentChain(parent, allById) { id -> folderColors[id] } },
                durationMinutes = it.durationMinutes,
                due = it.dueDate?.takeUnless { _ -> it.isComplete }?.let { d -> dueStatus(d, now) }
            )
        }
}
