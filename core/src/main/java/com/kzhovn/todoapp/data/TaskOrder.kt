package com.kzhovn.todoapp.data

// Sibling order everywhere (outliner, sequential parents): a sort key, the manual position if the list
// was ever reordered, else the (creation-ordered) id. Positions sit far below ids, so never-reordered
// newcomers land at the end; id breaks ties.
val TaskOrder: Comparator<Task> = compareBy<Task>({ sortKey(it) }, { it.id })

private fun sortKey(task: Task) = task.position ?: task.id

// Room between positions, so a new task can usually slot in without moving its siblings.
private const val GAP = 1024L

// Where a new task goes: under a folder (or at the top level) the list leads with folders, then
// projects, then everything else, and a newcomer goes first in its group (a new folder, last of the
// folders). Under a task, project or sequential folder it stays last, so steps keep the order they
// were added in. Returns the positions to write (the new task's included). Normally that's
// just the new task: a key below the first task, or between the last folder and it. Only when there's
// no room left there are the siblings respaced (every write is a row to sync, so moving a whole
// folder's worth of tasks for each add was costly).
fun newTaskPositions(all: List<Task>, new: Task): Map<Long, Long> {
    val parent = new.parentId?.let { id -> all.firstOrNull { it.id == id } }
    if (parent != null && (parent.type != TaskType.FOLDER || parent.sequential)) return emptyMap()
    val siblings = all.filter { it.parentId == new.parentId && it.id != new.id }.sortedWith(TaskOrder).toMutableList()
    fun group(t: Task) = when (t.type) { TaskType.FOLDER -> 0; TaskType.PROJECT -> 1; else -> 2 }
    val at = siblings.takeWhile { if (new.type == TaskType.FOLDER) group(it) == 0 else group(it) < group(new) }.size
    val above = siblings.getOrNull(at - 1)?.let(::sortKey)
    val below = siblings.getOrNull(at)?.let(::sortKey) ?: return emptyMap() // nothing below: its id sorts last
    val key = when {
        above == null -> below - GAP
        below - above >= 2 -> above + (below - above) / 2
        else -> null
    }
    if (key != null) return mapOf(new.id to key)
    siblings.add(at, new)
    return renumber(siblings, new.id)
}

// The positions to write so `siblings` read in order, GAP apart: those that changed, plus the task being placed.
private fun renumber(siblings: List<Task>, placedId: Long): Map<Long, Long> =
    siblings.withIndex().filter { (i, t) -> t.position != (i + 1) * GAP || t.id == placedId }.associate { (i, t) -> t.id to (i + 1) * GAP }

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

// planMoveNextTo, but an item dragged onto a checklist section's heading goes into a section: dragged down
// onto it, first in that section; up onto it, last in the group above (the loose items, or the section
// before). Anything else moves as planMoveNextTo does.
fun planMoveItem(all: List<Task>, taskId: Long, anchorId: Long, after: Boolean): Move? {
    val anchor = all.firstOrNull { it.id == anchorId }?.takeIf { it.type == TaskType.SECTION } ?: return planMoveNextTo(all, taskId, anchorId, after)
    val groups = checklistGroups(anchor.parentId ?: return null, all)
    val at = groups.indexOfFirst { it.first?.id == anchorId }
    val (section, items) = groups.getOrNull(if (after) at else at - 1) ?: return null
    val beside = (if (after) items.firstOrNull { it.id != taskId } else items.lastOrNull { it.id != taskId })
        ?: return Move(section?.id ?: anchor.parentId, mapOf(taskId to GAP)) // an empty group: just into it
    return planMoveNextTo(all, taskId, beside.id, after = !after)
}

// Open projects whose subtasks are all done: time to complete the project or add the next step.
fun stalledProjects(tasks: List<Task>): List<Task> {
    val openChildren = tasks.filter { !it.isComplete && !it.type.isContainer }.groupBy { it.parentId }
    return tasks.filter { it.type == TaskType.PROJECT && !it.isComplete && openChildren[it.id].isNullOrEmpty() }
}
