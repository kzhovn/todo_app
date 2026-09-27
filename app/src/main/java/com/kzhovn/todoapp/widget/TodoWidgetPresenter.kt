package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.Task
import androidx.compose.ui.graphics.Color
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.data.walkParentChain

// A subtask or checklist item, shown under its row when the row is expanded.
data class WidgetChild(val id: Long, val title: String, val isComplete: Boolean)

data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false,
    val parentTitle: String? = null, // a subtask's parent, shown as "Parent: subtask"
    val parentId: Long? = null,
    val barColor: Color? = null, // the nearest folder's colour, as the app's rows show it
    val durationMinutes: Int? = null, // a timed task: the row gets a play button with its length
    val due: DueStatus? = null, // colours the checkbox ring
    val isChecklist: Boolean = false, // a count in the checkbox's place
    val children: List<WidgetChild> = emptyList() // what tapping the count expands, open ones first
)

object TodoWidgetPresenter {
    fun toRows(
        tasks: List<Task>,
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        allById: Map<Long, Task> = emptyMap(),
        folderColors: Map<Long, Color> = emptyMap(),
        effectiveDue: (Task) -> Long? = { it.dueDate },
        // Doing and Active: what's overdue or due today on top (urgentFirst), above the folder grouping.
        urgentOnTop: Boolean = false
    ): List<WidgetTaskRow> {
        val all = allById.values.filter { it.type != TaskType.FOLDER }
        // Grouped by top-level folder in the All tree's order (folderless last), like Active's sections.
        return sectionsByTopFolder(tasks, allById).flatMap { it.second }
            .let { if (urgentOnTop) urgentFirst(it, now, effectiveDue) else it }
            .map {
            WidgetTaskRow(
                it.id, it.title, it.isComplete, it.isStarred, it.isMaybe, subtaskCounts[it.id]?.takeIf { c -> c.second > 0 },
                it.isBackburner(now), parentTitle = subtaskParentTitle(it, allById), parentId = it.parentId,
                barColor = it.parentId?.let { parent -> walkParentChain(parent, allById) { id -> folderColors[id] } },
                durationMinutes = it.durationMinutes,
                due = effectiveDue(it)?.takeUnless { _ -> it.isComplete }?.let { d -> dueStatus(d, now) },
                isChecklist = it.type == TaskType.CHECKLIST,
                children = checklistItems(it.id, all).map { c -> WidgetChild(c.id, c.title, c.isComplete) }
            )
        }
    }
}
