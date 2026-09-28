package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.ChecklistItemOrder
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.Task
import kotlinx.serialization.Serializable
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.data.walkParentChain

// The widget's rows, shared by the phone's widget and the desktop tray (served as JSON by /api/tray).

// A subtask or checklist item, shown under its row when the row is expanded.
@Serializable
data class WidgetChild(val id: Long, val title: String, val isComplete: Boolean)

@Serializable
data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false,
    val parentTitle: String? = null, // a subtask's parent, shown as "Parent: subtask"
    val parentId: Long? = null,
    val barColor: Int? = null, // the nearest folder's colour (ARGB), as the app's rows show it
    val durationMinutes: Int? = null, // a timed task: the row gets a play button with its length
    val due: DueStatus? = null, // colours the checkbox ring
    val isChecklist: Boolean = false, // a count in the checkbox's place
    val hasNotes: Boolean = false, // the top bar marks it (the widget has no room)
    val children: List<WidgetChild> = emptyList() // what tapping the count expands, open ones first
)

object TodoWidgetPresenter {
    // The All list's tasks: every open task and checklist (not folders, projects or checklist items).
    fun allOpen(tasks: List<Task>, allById: Map<Long, Task>): List<Task> =
        tasks.filter { it.type.isDoable && !it.isComplete && !isChecklistItem(it, allById) }

    fun toRows(
        tasks: List<Task>,
        subtaskCounts: Map<Long, Pair<Int, Int>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        allById: Map<Long, Task> = emptyMap(),
        folderColors: Map<Long, Int> = emptyMap(), // ARGB, see folderColorsArgb
        effectiveDue: (Task) -> Long? = { it.dueDate },
        // Doing and Active: what's overdue or due today on top (urgentFirst), above the folder grouping.
        urgentOnTop: Boolean = false
    ): List<WidgetTaskRow> {
        // Each row's children, grouped once: looking them up per row made a long list quadratic.
        val childrenOf = allById.values.filter { it.type != TaskType.FOLDER }.groupBy { it.parentId }
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
                hasNotes = !it.notes.isNullOrBlank(),
                children = childrenOf[it.id].orEmpty().sortedWith(ChecklistItemOrder).map { c -> WidgetChild(c.id, c.title, c.isComplete) }
            )
        }
    }
}
