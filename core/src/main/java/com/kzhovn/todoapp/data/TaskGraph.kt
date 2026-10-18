package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.repository.createdAt

// Walks a parentId chain from startId until `visit` returns non-null. A revisited id stops the walk
// instead of looping forever, since nothing in the data layer forbids a parentId cycle.
fun <T> walkParentChain(startId: Long, allById: Map<Long, Task>, visit: (Long) -> T?): T? {
    val seen = mutableSetOf<Long>()
    var current: Long? = startId
    while (current != null && seen.add(current)) {
        visit(current)?.let { return it }
        current = allById[current]?.parentId
    }
    return null
}

// Would picking candidateId as editingTaskId's parent create a cycle? Walks up candidateId's
// parentId chain looking for editingTaskId — a hit means editingTaskId would become its own
// descendant (directly, as its own parent, or transitively through any chain length).
fun wouldCreateCycle(candidateId: Long, editingTaskId: Long, allById: Map<Long, Task>): Boolean =
    walkParentChain(candidateId, allById) { id -> true.takeIf { id == editingTaskId } } ?: false

// Is the task somewhere below ancestorId (at any depth)?
fun isUnder(task: Task, ancestorId: Long, allById: Map<Long, Task>): Boolean =
    task.parentId?.let { wouldCreateCycle(it, ancestorId, allById) } == true

// The rules for linking tasks, used wherever a link is offered or made (the app's pickers and
// repository, the web's add field, the server's service), so every device allows the same ones.

// May taskId go under newParentId? Not into itself or anything below it.
fun canMoveUnder(taskId: Long, newParentId: Long, allById: Map<Long, Task>): Boolean = !wouldCreateCycle(newParentId, taskId, allById)

// May taskId wait on dependsOnId? Not on itself, nor so that the two end up waiting on each other.
fun canDependOn(taskId: Long, dependsOnId: Long, edges: List<TaskDependency>): Boolean =
    dependsOnId != taskId && !wouldCreateDependencyCycle(dependsOnId, taskId, edges)

private fun canDependOn(taskId: Long, dependsOnId: Long, adjacency: Map<Long, List<Long>>): Boolean =
    dependsOnId != taskId && !wouldCreateDependencyCycle(dependsOnId, taskId, adjacency)

// An edit's links as every device saves them, whatever the form sent. A parent that would make a loop,
// or is gone, falls back to the stored one.
fun safeParent(task: Task, storedParentId: Long?, allById: Map<Long, Task>): Long? =
    task.parentId?.let { p -> if (p in allById && canMoveUnder(task.id, p, allById)) p else storedParentId }

// A dependency that would make a loop, or on a folder (never completed), is dropped; a folder waits on nothing.
fun safeDependencies(task: Task, dependsOn: Set<Long>, allById: Map<Long, Task>, edges: List<TaskDependency>): Set<Long> {
    if (task.type == TaskType.FOLDER) return emptySet()
    val adjacency = dependsOnMap(edges)
    return dependsOn.filterTo(mutableSetOf()) { allById[it]?.type?.isLinkable == true && canDependOn(task.id, it, adjacency) }
}

enum class LinkKind { SUBTASK, PREREQUISITE, DEPENDENT }

// The open tasks that could be linked to `task` as `kind` and aren't already, by title.
fun linkCandidates(task: Task, kind: LinkKind, all: List<Task>, edges: List<TaskDependency>): List<Task> {
    val byId = all.associateBy { it.id }
    // Events are offered from the calendar instead (see planLinkEvent).
    val open = all.filter { it.id != task.id && !it.isComplete && it.type != TaskType.EVENT }
    val adjacency by lazy { dependsOnMap(edges) }
    return when (kind) {
        LinkKind.SUBTASK -> open.filter { !it.type.isContainer && (task.id == 0L || it.parentId != task.id) && canMoveUnder(it.id, task.id, byId) }
        LinkKind.PREREQUISITE -> {
            val linked = edges.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet()
            open.filter { it.type.isLinkable && it.id !in linked && canDependOn(task.id, it.id, adjacency) }
        }
        LinkKind.DEPENDENT -> {
            val linked = edges.filter { it.dependsOnTaskId == task.id }.map { it.taskId }.toSet()
            open.filter { it.type.isLinkable && it.id !in linked && canDependOn(it.id, task.id, adjacency) }
        }
    }.sortedBy { it.title.lowercase() }
}

// The related pickers' list, nearest first: tasks beside this one (its siblings and its own subtasks), then
// the rest of its folder, then ones added in the last two weeks (newest first), then everything else.
// Empty sections are left out.
fun nearbyFirst(task: Task, candidates: List<Task>, all: List<Task>, now: Long): List<Pair<String, List<Task>>> {
    val byId = all.associateBy { it.id }
    var rest = candidates
    // A checklist's items aren't offered as nearby: they'd crowd out the tasks (search still finds them).
    fun take(pick: (Task) -> Boolean): List<Task> = rest.partition { pick(it) && !isChecklistItem(it, byId) }.also { rest = it.second }.first
    val beside = take { (task.parentId != null && it.parentId == task.parentId) || (task.id != 0L && it.parentId == task.id) }
    val folder = byId[task.parentId]?.takeIf { it.type == TaskType.FOLDER } ?: nearestFolder(task, byId)
    val inFolder = folder?.let { f -> take { isUnder(it, f.id, byId) } }.orEmpty()
    val recent = take { t -> createdAt(t)?.let { now - it < 14 * DAY_MS } == true }.sortedByDescending { it.id }
    return listOf(
        Labels.BESIDE_THIS to beside,
        Labels.inFolder(folder?.title.orEmpty()) to inFolder,
        Labels.ADDED_RECENTLY to recent,
        Labels.EVERYTHING_ELSE to rest
    ).filter { it.second.isNotEmpty() }
}

// Folders (and whatever else is passed, e.g. checklists) as a tree in sibling order, each with its depth:
// the folder pickers on the phone and the web. Leaves out `excluding` and everything below it (where a
// task can't be moved: into itself).
fun folderTree(items: List<Task>, excluding: Long? = null, allById: Map<Long, Task> = items.associateBy { it.id }): List<Pair<Task, Int>> {
    val visible = if (excluding != null) items.filter { !wouldCreateCycle(it.id, excluding, allById) } else items
    val ids = visible.map { it.id }.toSet()
    val byParent = visible.groupBy { it.parentId?.takeIf { p -> p in ids } }
    fun walk(parent: Long?, depth: Int): List<Pair<Task, Int>> =
        byParent[parent].orEmpty().sortedWith(TaskOrder).flatMap { listOf(it to depth) + walk(it.id, depth + 1) }
    return walk(null, 0)
}
