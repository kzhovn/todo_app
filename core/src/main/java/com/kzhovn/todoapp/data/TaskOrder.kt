package com.kzhovn.todoapp.data

// Sibling order everywhere (outliner, sequential parents): a manual position if the list was ever
// reordered, else creation order. Positions are small (1..n) and ids huge, so never-reordered
// newcomers land at the end; id breaks ties.
val TaskOrder: Comparator<Task> = compareBy<Task>({ it.position ?: it.id }, { it.id })

// Where a new task goes: under a folder (or at the top level) it's first, just below the folders
// that lead the list; under a task, project or sequential folder it stays last, so steps keep the
// order they were added in. Returns the positions to write (the new task's included), renumbering the siblings 1..n.
fun newTaskPositions(all: List<Task>, new: Task): Map<Long, Long> {
    val parent = new.parentId?.let { id -> all.firstOrNull { it.id == id } }
    if (parent != null && (parent.type != TaskType.FOLDER || parent.sequential)) return emptyMap()
    val siblings = all.filter { it.parentId == new.parentId && it.id != new.id }.sortedWith(TaskOrder).toMutableList()
    siblings.add(siblings.takeWhile { it.type == TaskType.FOLDER }.size, new)
    return renumber(siblings, new.id)
}

// The positions to write so `siblings` read 1..n in order: those that changed, plus the task being placed.
private fun renumber(siblings: List<Task>, placedId: Long): Map<Long, Long> =
    siblings.withIndex().filter { (i, t) -> t.position != i + 1L || t.id == placedId }.associate { (i, t) -> t.id to i + 1L }

// A move right before/after `anchorId`, under the anchor's parent: that sibling list renumbered 1..n
// (renumbering never runs out of room between neighbours, and rewrites only a few rows at one user's
// scale). Null when the task or anchor is missing, or the move would put a task inside itself.
data class Move(val parentId: Long?, val positions: Map<Long, Long>)

fun planMoveNextTo(all: List<Task>, taskId: Long, anchorId: Long, after: Boolean): Move? {
    val byId = all.associateBy { it.id }
    val task = byId[taskId] ?: return null
    val anchor = byId[anchorId]?.takeIf { it.id != taskId } ?: return null
    val parentId = anchor.parentId
    if (parentId != null && wouldCreateCycle(parentId, taskId, byId)) return null
    val siblings = all.filter { it.parentId == parentId && it.id != taskId }.sortedWith(TaskOrder).toMutableList()
    siblings.add(siblings.indexOf(anchor) + if (after) 1 else 0, task)
    return Move(parentId, renumber(siblings, taskId))
}

// Open projects whose subtasks are all done: time to complete the project or add the next step.
fun stalledProjects(tasks: List<Task>): List<Task> {
    val openChildren = tasks.filter { !it.isComplete && it.type != TaskType.FOLDER }.groupBy { it.parentId }
    return tasks.filter { it.type == TaskType.PROJECT && !it.isComplete && openChildren[it.id].isNullOrEmpty() }
}
