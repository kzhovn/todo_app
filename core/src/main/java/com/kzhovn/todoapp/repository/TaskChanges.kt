package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.newTaskPositions
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.resolvesBy
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

// A change to the task list worked out once, here, and carried out by each side's storage (the phone's
// TaskRepository, the server's TaskService), so completing a task means exactly the same thing on both.
// Carried out creates first, then updates in order (a later one for the same task wins), then deletes.
data class TaskChanges(
    val updates: List<Task> = emptyList(), // existing tasks, as they should now be
    val creates: List<Pair<Task, Set<Long>>> = emptyList(), // new tasks, with their context ids
    val deletes: Set<Long> = emptySet()
) {
    operator fun plus(other: TaskChanges) = TaskChanges(updates + other.updates, creates + other.creates, deletes + other.deletes)

    // The task list as it will be afterwards, for planning a next step on top of this one.
    fun applyTo(all: List<Task>): List<Task> {
        val updated = updates.associateBy { it.id }
        return all.filter { it.id !in deletes }.map { updated[it.id] ?: it } + creates.map { it.first }
    }
}

private fun descendants(id: Long, all: List<Task>): List<Task> = all.associateBy { it.id }.let { byId -> all.filter { isUnder(it, id, byId) } }

// Completes a task. A repeating one gets its next instance, which keeps the task's contexts (not its
// dependencies) and gets fresh copies of its subtasks.
fun planComplete(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, now: Long): TaskChanges {
    val task = all.firstOrNull { it.id == id }?.takeUnless { it.isComplete } ?: return TaskChanges()
    val completed = task.completed(now)
    val next = RecurrenceEngine.nextInstance(completed, now) ?: return TaskChanges(updates = listOf(completed))
    val copies = listOf(id to next) + RecurrenceEngine.successorSubtasks(completed, next, descendants(id, all))
    return TaskChanges(updates = listOf(completed), creates = copies.map { (originalId, copy) -> copy to contexts[originalId].orEmpty() })
}

// Reopens a task, taking back the next instance its completion made if that hasn't been touched since.
fun planUncomplete(all: List<Task>, id: Long): TaskChanges {
    val task = all.firstOrNull { it.id == id }?.takeIf { it.isComplete } ?: return TaskChanges()
    val successor = RecurrenceEngine.untouchedSuccessor(task, all)
    val gone = successor?.let { s -> descendants(s.id, all).map { it.id }.toSet() + s.id }.orEmpty()
    return TaskChanges(updates = listOf(task.copy(isComplete = false, completedAt = null)), deletes = gone)
}

// Completes every open descendant first, each on its own (so a repeating subtask still makes its next
// instance), then the task itself.
fun planCompleteWithDescendants(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, now: Long): TaskChanges =
    planEach(all, descendants(id, all).filter { it.type != TaskType.FOLDER && !it.isComplete }.map { it.id } + id) { current, next ->
        planComplete(current, contexts, next, now)
    }

// Completing a checklist with items still unchecked: they either move to a fresh copy of the list (same
// place, contexts and star), or are completed along with it.
fun planCompleteChecklist(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, moveUncheckedToNewList: Boolean, now: Long): TaskChanges {
    val checklist = all.firstOrNull { it.id == id } ?: return TaskChanges()
    val unchecked = all.filter { it.parentId == id && !it.isComplete }
    val move = if (moveUncheckedToNewList && unchecked.isNotEmpty()) {
        val copy = checklist.copy(id = newId(), position = null, recurrenceType = null, recurrenceRule = null)
        TaskChanges(creates = listOf(copy to contexts[id].orEmpty()), updates = unchecked.map { it.copy(parentId = copy.id) })
    } else TaskChanges()
    return move + planCompleteWithDescendants(move.applyTo(all), contexts, id, now)
}

// Waiting items whose date has come resolve themselves (see Task.resolvesBy).
fun planResolveWaiting(all: List<Task>, contexts: Map<Long, Set<Long>>, now: Long): TaskChanges =
    planEach(all, all.filter { it.resolvesBy(now) }.map { it.id }) { current, next -> planComplete(current, contexts, next, now) }

// Plans each step on the list as the steps before it leave it. An update to a task an earlier step
// already changed replaces that one, so carrying the changes out in order gives the same result.
private fun planEach(all: List<Task>, ids: List<Long>, step: (List<Task>, Long) -> TaskChanges): TaskChanges {
    var current = all
    var total = TaskChanges()
    for (id in ids) {
        val change = step(current, id)
        current = change.applyTo(current)
        total += change
    }
    return total
}

// A new task, placed among its siblings as new tasks are (newTaskPositions: it may move a few of them),
// with the write rules applied. A task with no id yet gets one.
fun planCreate(all: List<Task>, task: Task, contextIds: Set<Long> = emptySet(), now: Long): TaskChanges {
    val new = (if (task.id == 0L) task.copy(id = newId()) else task).withRules(now)
    val positions = newTaskPositions(all, new)
    return TaskChanges(
        creates = listOf(new.copy(position = positions[new.id] ?: new.position) to contextIds),
        updates = all.mapNotNull { t -> positions[t.id]?.let { t.copy(position = it) } }
    )
}

// Carries out a quick add (see planQuickAdd): the task with its contexts and items, pinned or focused if
// asked, or the items into an existing checklist. Returns the changes and the task's id (the checklist's,
// for items). `star`: added while looking at Doing, so it shows up there.
fun planQuickAddWrite(all: List<Task>, add: QuickAdd, defaultParent: Long?, now: Long, star: Boolean = false): Pair<TaskChanges, Long?> {
    if (add.isEmpty) return TaskChanges() to null
    var current = all
    var total = TaskChanges()
    fun step(change: TaskChanges) { current = change.applyTo(current); total += change }
    fun items(parent: Long) = add.items.forEach { step(planCreate(current, Task(title = it, parentId = parent), now = now)) }
    add.intoChecklist?.let { id -> items(id); return total to id }
    val task = add.task ?: return TaskChanges() to null
    val created = planCreate(current, task.copy(parentId = task.parentId ?: defaultParent, isStarred = task.isStarred || (star && !task.isMaybe)), add.contextIds, now)
    step(created)
    val id = created.creates.single().first.id
    items(id)
    if (add.focus) step(TaskChanges(updates = CurrentTask.focus(current, id, now)))
    else if (add.pin) step(TaskChanges(updates = CurrentTask.pin(current, id, now)))
    return total to id
}

// "Today only" tasks past their day go, with everything under them (deleted, not kept as done).
fun planPurgeExpired(all: List<Task>, now: Long): TaskChanges =
    TaskChanges(deletes = all.filter { it.isExpired(now) }.flatMap { t -> descendants(t.id, all).map { it.id } + t.id }.toSet())

// A checklist's checked items are gone for good (after asking): a groceries list would otherwise fill up.
fun planClearChecked(all: List<Task>, checklistId: Long): TaskChanges =
    TaskChanges(deletes = all.filter { it.parentId == checklistId && it.isComplete }.flatMap { t -> descendants(t.id, all).map { it.id } + t.id }.toSet())

// Unchecks every item, each as reopening it would (a repeating item's untouched next one is taken back).
fun planUncheckAll(all: List<Task>, checklistId: Long): TaskChanges =
    planEach(all, all.filter { it.parentId == checklistId && it.isComplete }.map { it.id }) { current, id -> planUncomplete(current, id) }

// Only the direct children move out, to the top level (at the end), so they survive as tasks of their own.
fun planPromoteChildren(all: List<Task>, id: Long): TaskChanges =
    TaskChanges(updates = all.filter { it.parentId == id }.map { it.copy(parentId = null, position = null) })
