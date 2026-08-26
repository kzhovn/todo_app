package com.kzhovn.todoapp.data

data class EffectiveTask(
    val task: Task,
    val effectiveStartDate: Long?,
    val effectiveDueDate: Long?,
    val effectiveContextIds: Set<Long>,
    val effectiveIcon: String?
)

// Start date, due date, contexts, and icon flow down from parent to child (through folders, and
// through any number of ancestor levels) when the child hasn't set its own value. Recurrence,
// dependencies, isStarred, sequential, and isComplete are deliberately NOT inherited — they either
// don't have a sensible "unset falls back to parent" meaning, or (recurrence) are deferred pending
// further design.
fun resolveEffective(
    task: Task,
    allById: Map<Long, Task>,
    contextsByTaskId: Map<Long, Set<Long>>
): EffectiveTask {
    fun <T : Any> walkUp(getValue: (Task) -> T?): T? {
        val seen = mutableSetOf<Long>() // nothing forbids a parentId cycle; don't spin forever on one
        var current: Task? = task
        while (current != null && seen.add(current.id)) {
            getValue(current)?.let { return it }
            current = current.parentId?.let { allById[it] }
        }
        return null
    }

    return EffectiveTask(
        task = task,
        effectiveStartDate = walkUp { it.startDate },
        effectiveDueDate = walkUp { it.dueDate },
        // An empty context set counts as "unset" and keeps walking; there is no way to express
        // "explicitly no contexts, don't inherit".
        effectiveContextIds = walkUp { contextsByTaskId[it.id]?.ifEmpty { null } }.orEmpty(),
        effectiveIcon = walkUp { it.icon }
    )
}
