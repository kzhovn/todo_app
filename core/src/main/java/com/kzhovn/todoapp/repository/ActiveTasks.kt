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
import com.kzhovn.todoapp.data.zoned

// Shared by every Active/Doing caller (app, widget, server) so all agree on "now" in the default timezone.
fun minuteOfDay(epochMillis: Long): Int = zoned(epochMillis).let { it.hour * 60 + it.minute }

// Su=0 .. Sa=6, as ContextTimeWindow.daysMask counts them.
fun dayOfWeekMask(epochMillis: Long): Int = 1 shl (zoned(epochMillis).dayOfWeek.value % 7)

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

    // Under a sequential parent, only the steps up now (stepsUpNow: the first open one, and those beside it) are workable.
    val upNow = mutableMapOf<Long, Set<Long>>()
    fun isSequentiallyBlocked(task: Task): Boolean {
        val parent = task.parentId?.let { allById[it] } ?: return false
        if (!parent.sequential) return false
        return task.id !in upNow.getOrPut(parent.id) {
            stepsUpNow(childrenByParentId[parent.id].orEmpty().filter { !it.isComplete }.sortedWith(TaskOrder), now).map { it.id }.toSet()
        }
    }

    fun isContextSatisfied(contextId: Long): Boolean {
        val ctx = allContexts[contextId] ?: return true
        return when (ctx.type) {
            ContextType.PLACE -> ctx.isCurrentlySatisfied
            ContextType.LABEL -> true
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
