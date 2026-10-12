package com.kzhovn.todoapp.data

// Where a new task goes when no folder is given (quick add, the bot, the web), if a folder by this name exists.
const val DEFAULT_FOLDER = "Personal"

// A folder by name, ignoring case and stray spaces (Discord's `--work:`, the default folder).
fun findFolder(tasks: Collection<Task>, name: String): Task? =
    tasks.firstOrNull { it.type == TaskType.FOLDER && it.title.trim().equals(name.trim(), ignoreCase = true) }

// (done, total) subtasks per task that has any, for the rows' "2/5". Subfolders don't count; a checklist's
// count takes in its sections' items (and each section has its own).
fun subtaskCounts(tasks: Collection<Task>): Map<Long, Pair<Int, Int>> {
    val byId = tasks.associateBy { it.id }
    return tasks.filter { !it.type.isContainer && it.parentId != null }
        .flatMap { t -> listOfNotNull(t.parentId, byId[t.parentId]?.takeIf { it.type == TaskType.SECTION }?.parentId).map { it to t } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, kids) -> kids.count { it.isComplete } to kids.size }
}

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

// The nearest folder above a task, e.g. for its row's colour bar.
fun nearestFolder(task: Task, byId: Map<Long, Task>): Task? =
    task.parentId?.let { start -> walkParentChain(start, byId) { id -> byId[id]?.takeIf { it.type == TaskType.FOLDER } } }

// The folder a task sits directly in (null: none, or it's a subtask): where related work made from it goes.
fun folderOf(task: Task, byId: Map<Long, Task>): Long? = task.parentId?.takeIf { byId[it]?.type == TaskType.FOLDER }
