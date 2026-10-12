package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.isContainer
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.dueStatus
import com.kzhovn.todoapp.data.DueStatus
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.data.walkParentChain

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
    val dues = DueDates(all, dependencies)

    // Each task's open prerequisites.
    val waitingOn = dependencies
        .filter { edge -> allById[edge.dependsOnTaskId]?.isComplete == false }
        .groupBy({ it.taskId }, { it.dependsOnTaskId })
    val blockedByDependency = waitingOn.keys

    // A project never shows itself, only its steps, so a project waiting on a prerequisite holds its steps
    // back too. Not one inside the project, though: that step would be waiting on itself.
    fun inBlockedProject(task: Task): Boolean = task.parentId?.let { start ->
        walkParentChain(start, allById) { id ->
            val project = allById[id]?.takeIf { it.type == TaskType.PROJECT } ?: return@walkParentChain null
            true.takeIf { waitingOn[project.id].orEmpty().any { p -> allById[p]?.let { it.id != task.id && !isUnder(it, project.id, allById) } == true } }
        }
    } == true

    val childrenByParentId = all.groupBy { it.parentId }

    // Under a sequential parent, only the first incomplete child (in sibling order) is workable.
    val firstOpenChild = mutableMapOf<Long, Task?>()
    fun isSequentiallyBlocked(task: Task): Boolean {
        val parent = task.parentId?.let { allById[it] } ?: return false
        if (!parent.sequential) return false
        val first = firstOpenChild.getOrPut(parent.id) { childrenByParentId[parent.id].orEmpty().filter { !it.isComplete }.minWithOrNull(TaskOrder) } ?: return false
        return first.id != task.id
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
        // A checklist is workable like a task (its open items never block it); its items aren't listed.
        if (!task.type.isDoable) return@filter false
        if (task.isComplete || task.isMaybe || task.isExpired(now) || isChecklistItem(task, allById)) return@filter false
        if (task.id in blockedByDependency || isSequentiallyBlocked(task) || inBlockedProject(task)) return@filter false
        // A task waits on its open subtasks, as on a dependency, unless set not to. A checklist doesn't:
        // its items are ticked off inside it.
        if (task.type == TaskType.TASK && !task.activeWithSubtasks && childrenByParentId[task.id].orEmpty().any { !it.type.isContainer && !it.isComplete && !it.isExpired(now) }) return@filter false
        val effective = resolveEffective(task, allById, contextsByTaskId)
        // Due today or overdue, a time context (work hours, evenings) doesn't hold it back; a place one still does.
        val urgent = dues.at(task)?.let { dueStatus(it, now) != DueStatus.LATER } == true
        (effective.effectiveStartDate == null || effective.effectiveStartDate <= now) &&
            effective.effectiveContextIds.all { isContextSatisfied(it) || (urgent && allContexts[it]?.type == ContextType.TIME) }
    }
}

// Active as if every context held: held back only by its own state (a start to come, a prerequisite, an
// earlier step, open subtasks, a maybe), not by where or when you are. The nudges' Doing goes by it.
fun computeActiveIgnoringContexts(all: List<Task>, dependencies: List<TaskDependency>, now: Long): List<Task> =
    computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), dependencies, now)

// The All tree dims what isn't workable for a reason other than contexts (`workable`: the ids of
// computeActiveIgnoringContexts), and anything else with a start still to come (a project, say).
fun dimmedInTree(task: Task, workable: Set<Long>, byId: Map<Long, Task>, now: Long): Boolean =
    task.startsLater(now) || (task.type.isDoable && !task.isComplete && !isChecklistItem(task, byId) && task.id !in workable)
