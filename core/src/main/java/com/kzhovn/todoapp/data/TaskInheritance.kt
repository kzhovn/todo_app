package com.kzhovn.todoapp.data

data class EffectiveTask(
    val task: Task,
    val effectiveStartDate: Long?,
    val effectiveContextIds: Set<Long>,
    val effectiveIcon: String?
)

// Start date, due date, contexts, and icon flow down from parent to child (through folders, and
// through any number of ancestor levels) when the child hasn't set its own value. Recurrence,
// dependencies, isStarred, sequential, and isComplete are deliberately NOT inherited — they either
// don't have a sensible "unset falls back to parent" meaning, or (recurrence) are deferred pending
// further design. The due date is worked out by DueDates, below; resolveEffective has the rest.

// A due date as shown: `forTask` is set when it comes from an open task waiting on this one (see DueDates).
data class ShownDue(val at: Long, val forTask: Task? = null)

// The due date each task shows: its own, else its parent's (as above); or, when sooner, that of an open
// task waiting on it, since a prerequisite has to be done by then too. Built once per list, each task
// looked up once.
class DueDates(tasks: Collection<Task>, edges: Collection<TaskDependency>) {
    private val byId = tasks.associateBy { it.id }
    private val waitingOn = edges.groupBy({ it.dependsOnTaskId }, { it.taskId })
    private val known = HashMap<Long, ShownDue?>()

    fun of(task: Task): ShownDue? = resolve(task.id, HashSet())
    fun at(task: Task): Long? = of(task)?.at

    private fun resolve(id: Long, seen: MutableSet<Long>): ShownDue? {
        if (id in known) return known[id]
        val task = byId[id]
        if (task == null || !seen.add(id)) return null // seen: a dependency cycle (canDependOn prevents them)
        val inherited = task.dueDate?.let { ShownDue(it) } ?: task.parentId?.let { resolve(it, seen) }
        val forWaiting = waitingOn[id].orEmpty().mapNotNull(byId::get).filter { !it.isComplete }
            .mapNotNull { waiting -> resolve(waiting.id, seen)?.let { ShownDue(it.at, waiting) } }
            .minByOrNull { it.at }
        // A tie keeps its own (unmarked) date.
        return (if (forWaiting != null && (inherited == null || forWaiting.at < inherited.at)) forWaiting else inherited)
            .also { seen.remove(id); known[id] = it }
    }
}

fun resolveEffective(
    task: Task,
    allById: Map<Long, Task>,
    contextsByTaskId: Map<Long, Set<Long>>
): EffectiveTask {
    fun <T : Any> walkUp(getValue: (Task) -> T?): T? =
        walkParentChain(task.id, allById) { id -> allById[id]?.let(getValue) }

    return EffectiveTask(
        task = task,
        effectiveStartDate = walkUp { it.startDate },
        // An empty context set counts as "unset" and keeps walking; there is no way to express
        // "explicitly no contexts, don't inherit".
        effectiveContextIds = walkUp { contextsByTaskId[it.id]?.ifEmpty { null } }.orEmpty(),
        effectiveIcon = walkUp { it.icon }
    )
}
