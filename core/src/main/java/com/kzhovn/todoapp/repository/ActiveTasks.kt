package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.isContainer
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.matchingEvents
import com.kzhovn.todoapp.data.resolvesBy
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
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.openByContext

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
    todayMask: Int = dayOfWeekMask(now),
    events: List<CalendarEvent> = emptyList()
): List<Task> {
    val allById = all.associateBy { it.id }
    val allContexts = contexts.associateBy { it.id }
    val states = ContextStates(contexts, timeWindows, events, now, currentMinuteOfDay, todayMask)
    val dues = DueDates(all, dependencies)

    // Each task's open prerequisites. An event that's over no longer holds anything back, even before it's resolved.
    val waitingOn = dependencies
        .filter { edge -> allById[edge.dependsOnTaskId]?.let { !it.isComplete && !it.resolvesBy(now) } == true }
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

    fun isContextSatisfied(contextId: Long): Boolean = states.holds(contextId)

    return all.filter { task ->
        // A checklist is workable like a task (its open items never block it); its items aren't listed.
        if (!task.type.isDoable) return@filter false
        if (task.isComplete || task.isMaybe || task.isExpired(now) || isChecklistItem(task, allById)) return@filter false
        if (task.id in blockedByDependency || isSequentiallyBlocked(task) || inBlockedProject(task)) return@filter false
        // A task waits on its open subtasks, as on a dependency, unless set not to. A checklist doesn't:
        // its items are ticked off inside it.
        if (task.type == TaskType.TASK && !task.activeWithSubtasks && childrenByParentId[task.id].orEmpty().any { !it.type.isContainer && !it.isComplete && !it.isExpired(now) }) return@filter false
        val effective = resolveEffective(task, allById, contextsByTaskId)
        // Due today or overdue, a time context (work hours, evenings) doesn't hold it back; a place one, or one
        // turned off by hand, still does.
        val urgent = dues.at(task)?.let { dueStatus(it, now) != DueStatus.LATER } == true
        (effective.effectiveStartDate == null || effective.effectiveStartDate <= now) &&
            effective.effectiveContextIds.all { isContextSatisfied(it) || (urgent && allContexts[it]?.let { c -> c.type == ContextType.TIME && c.forced == null } == true) }
    }
}

// Whether each context holds now: as set by hand, else by its wifi, its time windows or its events, and not while a
// context it's off while holds by itself (as set by hand, else by its own rule; no chains, so no loops). A filter-only
// one always does, unless switched Off. Unknown ids hold.
class ContextStates(
    contexts: List<TaskContext>, windows: List<ContextTimeWindow>, private val events: List<CalendarEvent>, private val now: Long,
    private val minute: Int = minuteOfDay(now), private val mask: Int = dayOfWeekMask(now)
) {
    private val byId = contexts.associateBy { it.id }
    private val windowsByContext = windows.groupBy { it.contextId }

    fun holds(id: Long): Boolean {
        val ctx = byId[id] ?: return true
        return ctx.forced ?: (ownHolds(ctx) && ctx.offWhile.none { other -> other != id && byId[other]?.let { it.forced ?: ownHolds(it) } == true })
    }

    private fun ownHolds(ctx: TaskContext): Boolean =
        if (ctx.type == ContextType.EVENTS) matchingEvents(ctx, events).any { it.isOn(now) }
        else contextHolds(ctx.copy(forced = null), windowsByContext[ctx.id].orEmpty(), minute, mask)

    // The event that has it held, or off, now: "Off until 15:00 (Dentist)".
    fun eventNow(id: Long): CalendarEvent? {
        val ctx = byId[id] ?: return null
        val events = if (ctx.type == ContextType.EVENTS) matchingEvents(ctx, events)
            else ctx.offWhile.mapNotNull { byId[it] }.filter { it.type == ContextType.EVENTS }.flatMap { matchingEvents(it, events) }
        return events.filter { it.isOn(now) }.maxByOrNull { it.end }
    }
}

// Whether a context holds now by itself: as set by hand, else by its wifi or its time windows. A filter-only one
// always does, unless switched Off. Events and "off while" need the others: see ContextStates.
fun contextHolds(ctx: TaskContext, windows: List<ContextTimeWindow>, currentMinuteOfDay: Int, todayMask: Int): Boolean {
    ctx.forced?.let { return it }
    return when (ctx.type) {
        ContextType.PLACE -> ctx.isCurrentlySatisfied
        ContextType.LABEL -> true
        ContextType.EVENTS -> false
        ContextType.TIME -> windows.any { w ->
            (w.daysMask and todayMask) != 0 &&
                if (w.windowStartMinute <= w.windowEndMinute) {
                    currentMinuteOfDay in w.windowStartMinute..w.windowEndMinute
                } else { // window spans midnight
                    currentMinuteOfDay >= w.windowStartMinute || currentMinuteOfDay <= w.windowEndMinute
                }
        }
    }
}

fun contextHolds(ctx: TaskContext, windows: List<ContextTimeWindow>, now: Long): Boolean = contextHolds(ctx, windows, minuteOfDay(now), dayOfWeekMask(now))

// A context as its menus show it (the phone's @ menu and side menu, the web's sidebar): its open tasks, and
// whether it holds now.
data class ContextSummary(val context: TaskContext, val open: Int, val holds: Boolean) {
    // A filter-only one switched Off says so; a place or time one's grey dot does.
    val countText: String get() = if (!holds && context.type == ContextType.LABEL) "$open ${Labels.OFF}" else "$open"
}

fun contextSummaries(contexts: Collection<TaskContext>, windows: List<ContextTimeWindow>, all: Collection<Task>, contextsByTaskId: Map<Long, Set<Long>>, now: Long, events: List<CalendarEvent> = emptyList()): List<ContextSummary> {
    val open = openByContext(all, contextsByTaskId)
    val states = ContextStates(contexts.toList(), windows, events, now)
    return contexts.sortedBy { it.name.lowercase() }.map { ContextSummary(it, open[it.id] ?: 0, states.holds(it.id)) }
}

// A list filtered by a context shows its tasks as if it held, whatever it's doing now; never saved.
fun asIfOn(contexts: List<TaskContext>, contextId: Long?): List<TaskContext> =
    contexts.map { if (it.id == contextId) it.copy(forced = true) else it }

// Active as if every context held: held back only by its own state (a start to come, a prerequisite, an
// earlier step, open subtasks, a maybe), not by where or when you are. The nudges' Doing goes by it.
fun computeActiveIgnoringContexts(all: List<Task>, dependencies: List<TaskDependency>, now: Long): List<Task> =
    computeActiveTasks(all, emptyMap(), emptyList(), emptyList(), dependencies, now)

// The All tree dims what isn't workable for a reason other than contexts (`workable`: the ids of
// computeActiveIgnoringContexts), and anything else with a start still to come (a project, say).
fun dimmedInTree(task: Task, workable: Set<Long>, byId: Map<Long, Task>, now: Long): Boolean =
    task.startsLater(now) || (task.type.isDoable && !task.isComplete && !isChecklistItem(task, byId) && task.id !in workable)
