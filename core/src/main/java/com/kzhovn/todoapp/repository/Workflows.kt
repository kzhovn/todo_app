package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.resolvesBy
import com.kzhovn.todoapp.data.startedWait

// Workflows (docs/superpowers/specs/2026-10-17-workflows-design.md): an in-order parent with a timed wait among
// its steps (laundry: start wash, wait 50m, switch to dryer...). Ticking a step starts the wait after it, which
// resolves itself, and stars whichever step comes up next so it lands in Doing (unstar it to put it off).

fun isWorkflow(parent: Task?, all: Collection<Task>): Boolean =
    parent != null && parent.sequential && all.any { it.parentId == parent.id && it.isTimedWait }

private fun openSteps(parentId: Long, all: Collection<Task>): List<Task> =
    all.filter { it.parentId == parentId && !it.isComplete }.sortedWith(TaskOrder)

private fun starred(t: Task): Task? = t.takeIf { it.type.isDoable && !it.isStarred && !it.isMaybe }?.copy(isStarred = true)

// The steps of an in-order parent that are up now, from its open steps in order: the first, and past any that don't
// hold up the next (alongsideNext), the one after it too, and so on. A wait whose time has come counts as done,
// though nothing has resolved it yet. computeActiveTasks and planAdvance both go by it.
fun stepsUpNow(openInOrder: List<Task>, now: Long): List<Task> {
    val steps = openInOrder.filter { !it.resolvesBy(now) }
    val holding = steps.indexOfFirst { !it.alongsideNext }
    return if (holding < 0) steps else steps.take(holding + 1)
}

// The workflow moving on (from planComplete, with the step already ticked in `all`): the steps now up are starred,
// and a wait among them starts (timed from `from`). A wait holding up what's after it has those steps starred
// already, so they're in Doing when it ends. The last step done finishes a project (a repeating one makes its next
// run); a task with steps is then up itself.
fun planAdvance(all: List<Task>, contexts: Map<Long, Set<Long>>, parentId: Long, from: Long, now: Long): TaskChanges {
    val open = openSteps(parentId, all)
    if (open.isEmpty()) return if (all.firstOrNull { it.id == parentId }?.type == TaskType.PROJECT) planComplete(all, contexts, parentId, now) else TaskChanges()
    val up = stepsUpNow(open, now)
    val holdingWait = up.lastOrNull()?.takeIf { it.isTimedWait && !it.alongsideNext }
    val afterWait = holdingWait?.let { w -> stepsUpNow(open.dropWhile { it.id != w.id }.drop(1), now) }.orEmpty().filter { !it.isTimedWait }
    val starts = up.filter { it.isTimedWait && it.dueDate == null }.map { it.startedWait(from) }
    return TaskChanges(updates = starts + (up.filter { !it.isTimedWait } + afterWait).mapNotNull(::starred))
}

// Reopening a step takes back the wait ticking it started (it can't be running before the step is done).
fun planUnstartWait(all: List<Task>, reopened: Task): TaskChanges {
    val parent = all.firstOrNull { it.id == reopened.parentId }
    if (!isWorkflow(parent, all)) return TaskChanges()
    // One that doesn't hold up the next step didn't start anything.
    if (reopened.alongsideNext) return TaskChanges()
    val wait = openSteps(parent!!.id, all).dropWhile { it.id != reopened.id }.getOrNull(1)?.takeIf { it.isTimedWait && it.dueDate != null }
    return TaskChanges(updates = listOfNotNull(wait?.copy(dueDate = null, remindAt = null)))
}

// A timed wait made outside a workflow (`wait pasta ~10m`, a kitchen timer) starts at once; so does one added to a
// running workflow with nothing left before it. Others start when the step before them is ticked.
fun Task.startedIfDue(all: List<Task>, now: Long): Task {
    if (!isTimedWait || dueDate != null || isComplete) return this
    val parent = all.firstOrNull { it.id == parentId }
    val startsNow = parent?.sequential != true ||
        (openSteps(parent.id, all).none { it.id != id } && all.any { it.parentId == parent.id && it.isComplete })
    return if (startsNow) startedWait(now) else this
}

// The Running line's entries: each wait counting down, soonest first, with the step that comes after it.
data class RunningWait(val wait: Task, val next: Task?)

fun runningWaits(all: List<Task>, now: Long): List<RunningWait> =
    all.filter { it.isTimedWait && !it.isComplete && (it.dueDate ?: 0L) > now }.sortedBy { it.dueDate }.map { RunningWait(it, stepAfter(it, all)) }

// The open step after this one in its in-order parent ("then Switch to dryer").
fun stepAfter(step: Task, all: List<Task>): Task? = all.firstOrNull { it.id == step.parentId }?.takeIf { it.sequential }
    ?.let { p -> openSteps(p.id, all).dropWhile { it.id != step.id }.getOrNull(1) }
