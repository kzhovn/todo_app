package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.sectionsByTopFolder

// What to focus on next: Doing, or Active when Doing is empty. After finishing a step of something
// sequential, the step that's now unblocked always comes first.
fun nextFocusTasks(active: List<Task>, doing: List<Task>, finished: Task?, byId: Map<Long, Task>): List<Task> =
    (listOfNotNull(nextStep(active, finished, byId)) + doing.ifEmpty { active }).distinctBy { it.id }

private fun nextStep(active: List<Task>, finished: Task?, byId: Map<Long, Task>): Task? =
    finished?.parentId?.takeIf { byId[it]?.sequential == true }?.let { parent -> active.firstOrNull { it.parentId == parent } }

// The picker's search: any open task or checklist (not just Doing or Active), matched on its title.
// Checklist items stay out: they're ticked off inside their checklist's focus.
fun focusSearch(all: List<Task>, query: String): List<Task> {
    val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val byId = all.associateBy { it.id }
    return all.filter { it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) && it.title.contains(q, ignoreCase = true) }
        .sortedBy { it.title.lowercase() }
}

// The focus picker's lists, as Doing · Active · All chips: each led by a sequential project's next step (as
// above). All is every open task (no checklist items), grouped by top folder like the widget's All.
// `open` should already be in the mode's folder.
fun focusLists(active: List<Task>, doing: List<Task>, open: List<Task>, finished: Task?, byId: Map<Long, Task>): List<Pair<String, List<Task>>> {
    val all = sectionsByTopFolder(open.filter { it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) }, byId).flatMap { it.second }
    val next = listOfNotNull(nextStep(active, finished, byId))
    return listOf(Labels.DOING to doing, Labels.ACTIVE to active, Labels.ALL to all).map { (label, tasks) -> label to (next + tasks).distinctBy { it.id } }
}
