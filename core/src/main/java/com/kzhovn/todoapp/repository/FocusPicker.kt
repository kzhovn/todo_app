package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isDoable

// What to focus on next: Doing, or Active when Doing is empty. After finishing a step of something
// sequential, the step that's now unblocked always comes first.
fun nextFocusTasks(active: List<Task>, doing: List<Task>, finished: Task?, byId: Map<Long, Task>): List<Task> {
    val nextStep = finished?.parentId?.takeIf { byId[it]?.sequential == true }?.let { parent -> active.firstOrNull { it.parentId == parent } }
    return (listOfNotNull(nextStep) + doing.ifEmpty { active }).distinctBy { it.id }
}

// The picker's search: any open task or checklist (not just Doing or Active), matched on its title.
// Checklist items stay out: they're ticked off inside their checklist's focus.
fun focusSearch(all: List<Task>, query: String): List<Task> {
    val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val byId = all.associateBy { it.id }
    return all.filter { it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) && it.title.contains(q, ignoreCase = true) }
        .sortedBy { it.title.lowercase() }
}
