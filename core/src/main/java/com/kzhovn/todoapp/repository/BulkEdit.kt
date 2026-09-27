package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.Task

// One multi-edit applied to many tasks. Null means "leave as is"; the editable set is limited to
// what makes sense in bulk (no title, recurrence, or folder-ness).
data class BulkEdit(
    val starred: Boolean? = null,
    val maybe: Boolean? = null,
    val startDate: DateChange? = null,
    val dueDate: DateChange? = null,
    val moveTo: FolderChange? = null,
    val addContextIds: Set<Long> = emptySet(),
    val removeContextIds: Set<Long> = emptySet(),
    val dependsOnId: Long? = null
)

// The edit's own fields on one task; a move that would make a loop keeps the task where it is.
fun BulkEdit.applyTo(task: Task, allById: Map<Long, Task>): Task {
    val target = moveTo?.folderId
    val parentId = when {
        moveTo == null -> task.parentId
        target != null && wouldCreateCycle(target, task.id, allById) -> task.parentId
        else -> target
    }
    return task.copy(
        isStarred = starred ?: task.isStarred,
        isMaybe = maybe ?: task.isMaybe,
        startDate = startDate.let { if (it != null) it.date else task.startDate },
        dueDate = dueDate.let { if (it != null) it.date else task.dueDate },
        parentId = parentId
    )
}

// The edit's "depends on", unless it's the task itself or would make a dependency loop.
fun BulkEdit.blockerFor(taskId: Long, edges: List<TaskDependency>): Long? =
    dependsOnId?.takeIf { it != taskId && !wouldCreateDependencyCycle(it, taskId, edges) }

data class DateChange(val date: Long?)      // null date = clear it
data class FolderChange(val folderId: Long?) // null folder = move to top level
