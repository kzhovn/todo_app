package com.kzhovn.todoapp.data

// Active's foldable sections: each task under its outermost folder, in the All tree's folder order,
// then the folderless ones (a null folder). Sections with no tasks are left out.
fun sectionsByTopFolder(tasks: List<Task>, byId: Map<Long, Task>): List<Pair<Task?, List<Task>>> {
    val groups = tasks.groupBy { outermostFolder(it, byId) }
    return groups.keys.filterNotNull().sortedWith(TaskOrder).map { it to groups.getValue(it) } +
        listOfNotNull(groups[null]?.let { null to it })
}

// The outermost folder above a task (not counting the task itself), or null outside any folder.
fun outermostFolder(task: Task, byId: Map<Long, Task>): Task? {
    var top: Task? = null
    task.parentId?.let { start ->
        walkParentChain<Unit>(start, byId) { id -> byId[id]?.takeIf { it.type == TaskType.FOLDER }?.let { top = it }; null }
    }
    return top
}
