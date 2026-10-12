package com.kzhovn.todoapp.widget

import com.kzhovn.todoapp.data.isContainer
import com.kzhovn.todoapp.data.ChecklistItemOrder
import com.kzhovn.todoapp.data.checklistGroups
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.completeQuestion
import com.kzhovn.todoapp.data.formatDuration
import kotlinx.serialization.Serializable
import com.kzhovn.todoapp.data.sectionsByTopFolder
import com.kzhovn.todoapp.data.subtaskParentTitle
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.folderColorsArgb
import com.kzhovn.todoapp.data.subtaskCounts

// The widget's rows, shared by the phone's widget and the desktop tray (served as JSON by /api/tray).
// Deliberately leaner than the app's and web's list rows, to save space and keep the widgets clean: no
// high-priority "!", and due shows only as the checkbox ring's colour (no "due Fri" text).

// A subtask or checklist item, shown under its row when the row is expanded.
@Serializable
// subtaskQuestion: it has open subtasks, so ticking it asks about them first (completeQuestion); the widget
// opens the app's question, the top bar shows its own.
// isSection: a checklist section's heading (Clothes), followed by its items; not tickable.
data class WidgetChild(val id: Long, val title: String, val isComplete: Boolean, val subtaskQuestion: CompleteQuestion? = null, val isSection: Boolean = false)

@Serializable
data class WidgetTaskRow(
    val id: Long, val title: String, val isComplete: Boolean, val isStarred: Boolean, val isMaybe: Boolean = false,
    val subtasks: Pair<Int, Int>? = null, // (done, total), like the app's list rows
    val isBackburner: Boolean = false,
    val parentTitle: String? = null, // a subtask's parent, shown as "Parent: subtask"
    val parentId: Long? = null,
    val barColor: Int? = null, // the nearest folder's colour (ARGB), as the app's rows show it
    val durationMinutes: Int? = null, // a timed task: the row gets a play button with its length
    val durationText: String? = null, // that length in words, "1h 30m" (formatDuration), so the top bar needn't word it
    val due: DueStatus? = null, // colours the checkbox ring
    val isChecklist: Boolean = false, // a count in the checkbox's place
    val hasNotes: Boolean = false, // the top bar marks it (the widget has no room)
    val children: List<WidgetChild> = emptyList(), // what tapping the count expands, open ones first
    val subtaskQuestion: CompleteQuestion? = null // as WidgetChild's
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
        val childrenOf = allById.values.filter { !it.type.isContainer }.groupBy { it.parentId }
        // Open subtasks at any depth, as openDescendants counts them (a done one's open steps count too).
        fun openBelow(id: Long, seen: MutableSet<Long> = HashSet()): Int =
            childrenOf[id].orEmpty().filter { seen.add(it.id) }.sumOf { (if (it.isComplete) 0 else 1) + openBelow(it.id, seen) }
        fun question(task: Task) = completeQuestion(task, openBelow(task.id))
        // Grouped by top-level folder in the All tree's order (folderless last), like Active's sections.
        return sectionsByTopFolder(tasks, allById).flatMap { it.second }
            .let { if (urgentOnTop) urgentFirst(it, now, effectiveDue) else it }
            .map {
            WidgetTaskRow(
                it.id, it.title, it.isComplete, it.isStarred, it.isMaybe, subtaskCounts[it.id]?.takeIf { c -> c.second > 0 },
                it.isBackburner(now), parentTitle = subtaskParentTitle(it, allById), parentId = it.parentId,
                barColor = nearestFolder(it, allById)?.let { f -> folderColors[f.id] },
                durationMinutes = it.durationMinutes,
                durationText = it.durationMinutes?.let(::formatDuration),
                due = effectiveDue(it)?.takeUnless { _ -> it.isComplete }?.let { d -> dueStatus(d, now) },
                isChecklist = it.type == TaskType.CHECKLIST,
                hasNotes = !it.notes.isNullOrBlank(),
                children = if (it.type == TaskType.CHECKLIST) checklistGroups(it.id, allById.values).flatMap { (section, items) ->
                    listOfNotNull(section?.let { s -> WidgetChild(s.id, s.title, false, isSection = true) }) + items.map { c -> WidgetChild(c.id, c.title, c.isComplete, question(c)) }
                } else childrenOf[it.id].orEmpty().sortedWith(ChecklistItemOrder).map { c -> WidgetChild(c.id, c.title, c.isComplete, question(c)) },
                subtaskQuestion = question(it)
            )
        }
    }

    // Rows for `tasks` out of the whole list `all`, as the widget and the top bar both show them.
    fun rows(tasks: List<Task>, all: List<Task>, edges: List<TaskDependency>, now: Long, urgentOnTop: Boolean): List<WidgetTaskRow> {
        val byId = all.associateBy { it.id }
        return toRows(tasks, subtaskCounts(all), now, byId, folderColorsArgb(all), DueDates(all, edges)::at, urgentOnTop)
    }
}
