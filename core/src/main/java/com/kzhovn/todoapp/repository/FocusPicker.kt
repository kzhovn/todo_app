package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task

// What to focus on next: Doing, or Active when Doing is empty. After finishing a step of something
// sequential, the step that's now unblocked always comes first.
fun nextFocusTasks(active: List<Task>, doing: List<Task>, finished: Task?, byId: Map<Long, Task>): List<Task> {
    val nextStep = finished?.parentId?.takeIf { byId[it]?.sequential == true }?.let { parent -> active.firstOrNull { it.parentId == parent } }
    return (listOfNotNull(nextStep) + doing.ifEmpty { active }).distinctBy { it.id }
}
