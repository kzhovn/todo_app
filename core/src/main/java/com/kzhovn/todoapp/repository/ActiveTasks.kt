package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.resolveEffective

// Shared by every Active/Doing caller (app, widget, server) so all agree on "now" using the same
// Calendar arithmetic in the default timezone.
fun minuteOfDay(epochMillis: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
}

fun dayOfWeekMask(epochMillis: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return 1 shl (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1)
}

// Computed in Kotlin rather than SQL: effective start date and contexts are inherited from
// ancestors (see resolveEffective), which a single query can't express without several recursive
// CTEs. At this app's scale, holding the whole graph in memory is cheap.
fun computeActiveTasks(
    all: List<Task>,
    contextsByTaskId: Map<Long, Set<Long>>,
    contexts: List<TaskContext>,
    timeWindows: List<ContextTimeWindow>,
    dependencies: List<TaskDependency>,
    now: Long,
    currentMinuteOfDay: Int = minuteOfDay(now),
    todayMask: Int = dayOfWeekMask(now)
): List<Task> {
    val allById = all.associateBy { it.id }
    val allContexts = contexts.associateBy { it.id }
    val windowsByContext = timeWindows.groupBy { it.contextId }

    val blockedByDependency = dependencies
        .filter { edge -> allById[edge.dependsOnTaskId]?.isComplete == false }
        .map { it.taskId }
        .toSet()

    val childrenByParentId = all.groupBy { it.parentId }

    // Under a sequential parent, only the lowest-id (earliest-created) incomplete child is workable.
    fun isSequentiallyBlocked(task: Task): Boolean {
        val parent = task.parentId?.let { allById[it] } ?: return false
        if (!parent.sequential) return false
        val firstIncomplete = childrenByParentId[parent.id].orEmpty()
            .filter { !it.isComplete }
            .minByOrNull { it.id } ?: return false
        return firstIncomplete.id != task.id
    }

    fun isContextSatisfied(contextId: Long): Boolean {
        val ctx = allContexts[contextId] ?: return true
        return when (ctx.type) {
            ContextType.PLACE -> ctx.isCurrentlySatisfied
            ContextType.TIME -> windowsByContext[contextId].orEmpty().any { w ->
                (w.daysMask and todayMask) != 0 &&
                    if (w.windowStartMinute <= w.windowEndMinute) {
                        currentMinuteOfDay in w.windowStartMinute..w.windowEndMinute
                    } else { // window spans midnight
                        currentMinuteOfDay >= w.windowStartMinute || currentMinuteOfDay <= w.windowEndMinute
                    }
            }
        }
    }

    return all.filter { task ->
        if (task.type == TaskType.FOLDER || task.isComplete) return@filter false
        if (task.id in blockedByDependency || isSequentiallyBlocked(task)) return@filter false
        val effective = resolveEffective(task, allById, contextsByTaskId)
        (effective.effectiveStartDate == null || effective.effectiveStartDate <= now) &&
            effective.effectiveContextIds.all(::isContextSatisfied)
    }
}
