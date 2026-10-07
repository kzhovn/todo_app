package com.kzhovn.todoapp.data

// Walks a parentId chain starting at startId, visiting each id in turn until `visit` returns
// non-null or a cycle is detected — a revisited id stops the walk instead of looping forever,
// since nothing in the data layer forbids a duplicate parentId chain. Shared by wouldCreateCycle
// (searches for a specific id) and resolveEffective (resolves the first non-null field value).
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

enum class LinkKind { SUBTASK, PREREQUISITE, DEPENDENT }

// The open tasks that could be linked to `task` as `kind` and aren't already, by title.
fun linkCandidates(task: Task, kind: LinkKind, all: List<Task>, edges: List<TaskDependency>): List<Task> {
    val byId = all.associateBy { it.id }
    val open = all.filter { it.id != task.id && !it.isComplete }
    return when (kind) {
        LinkKind.SUBTASK -> open.filter { it.type != TaskType.FOLDER && (task.id == 0L || it.parentId != task.id) && canMoveUnder(it.id, task.id, byId) }
        LinkKind.PREREQUISITE -> {
            val linked = edges.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet()
            open.filter { it.type.isLinkable && it.id !in linked && canDependOn(task.id, it.id, edges) }
        }
        LinkKind.DEPENDENT -> {
            val linked = edges.filter { it.dependsOnTaskId == task.id }.map { it.taskId }.toSet()
            open.filter { it.type.isLinkable && it.id !in linked && canDependOn(it.id, task.id, edges) }
        }
    }.sortedBy { it.title.lowercase() }
}
