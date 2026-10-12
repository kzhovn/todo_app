package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.isContainer
import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.itemsIn
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.newTaskPositions
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.moveOutFolderId
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.resolvesBy
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

// A change to the task list worked out once, here, and carried out by each side's storage (the phone's
// TaskRepository, the server's TaskService), so completing a task means exactly the same thing on both.
// Carried out creates first (with `links`, dependencies among them), then updates in order (a later one for
// the same task wins), then deletes.
data class TaskChanges(
    val updates: List<Task> = emptyList(), // existing tasks, as they should now be
    val creates: List<Pair<Task, Set<Long>>> = emptyList(), // new tasks, with their context ids
    val deletes: Set<Long> = emptySet(),
    val links: List<TaskDependency> = emptyList()
) {
    operator fun plus(other: TaskChanges) = TaskChanges(updates + other.updates, creates + other.creates, deletes + other.deletes, links + other.links)

    // The task list as it will be afterwards, for planning a next step on top of this one.
    fun applyTo(all: List<Task>): List<Task> {
        val updated = updates.associateBy { it.id }
        return all.filter { it.id !in deletes }.map { updated[it.id] ?: it } + creates.map { it.first }
    }
}

private fun descendants(id: Long, all: List<Task>): List<Task> = all.associateBy { it.id }.let { byId -> all.filter { isUnder(it, id, byId) } }

// What "complete subtasks too" completes, and what the "N active subtasks" question counts.
fun openDescendants(id: Long, all: List<Task>): List<Task> = descendants(id, all).filter { !it.type.isContainer && !it.isComplete }

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
    planEach(all, openDescendants(id, all).map { it.id } + id) { current, next ->
        planComplete(current, contexts, next, now)
    }

// Completing a checklist with items still unchecked: they either move to a fresh copy of the list (same
// place, contexts and star), or are completed along with it.
// ponytail: the copy gets them loose, without their sections (keeping sections is on the backlog).
fun planCompleteChecklist(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, moveUncheckedToNewList: Boolean, now: Long): TaskChanges {
    val checklist = all.firstOrNull { it.id == id } ?: return TaskChanges()
    val unchecked = checklistItems(id, all).filter { !it.isComplete }
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
private fun <T> planEach(all: List<Task>, items: List<T>, step: (List<Task>, T) -> TaskChanges): TaskChanges {
    var current = all
    var total = TaskChanges()
    for (item in items) {
        val change = step(current, item)
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

// A checklist item typed with quick add's syntax (`plan` is planQuickAdd): what applies to an item is kept
// (dates, * ! ?, a timer, a repeat, a note, @contexts, today only); a folder, items of its own, pin or
// focus, or another type are not.
fun checklistItem(text: String, checklistId: Long, plan: (String) -> QuickAdd): Pair<Task, Set<Long>>? =
    plan(text).let { add -> add.task?.takeIf { it.title.isNotBlank() }?.let { it.copy(type = TaskType.TASK, parentId = checklistId) to add.contextIds } }

// Items into a checklist, each as checklistItem reads it, in order at its end.
fun planAddItems(all: List<Task>, checklistId: Long, items: List<String>, now: Long, plan: (String) -> QuickAdd): TaskChanges =
    planEach(all, items) { current, text ->
        checklistItem(text, checklistId, plan)?.let { (item, contextIds) -> planCreate(current, item, contextIds, now) } ?: TaskChanges()
    }

// A section at the end of a checklist, with its items (each read as checklistItem reads it).
fun planAddSection(all: List<Task>, checklistId: Long, title: String, items: List<String>, now: Long, plan: (String) -> QuickAdd): TaskChanges {
    val section = planCreate(all, Task(title = title, type = TaskType.SECTION, parentId = checklistId), now = now)
    return section + planAddItems(section.applyTo(all), section.creates.single().first.id, items, now, plan)
}

// Carries out a quick add (see planQuickAdd): the task with its contexts and items, pinned or focused if
// asked, or the items into an existing checklist. Returns the changes and the task's id (the checklist's,
// for items). `star`: added while looking at Doing, so it shows up there. `plan` reads each item.
// "use packing" makes the list from the template (planUseTemplate, with `contexts` and `edges` its tasks
// carry), titled and dated as typed.
fun planQuickAddWrite(
    all: List<Task>, add: QuickAdd, defaultParent: Long?, now: Long, plan: (String) -> QuickAdd, star: Boolean = false,
    contexts: Map<Long, Set<Long>> = emptyMap(), edges: List<TaskDependency> = emptyList()
): Pair<TaskChanges, Long?> {
    if (add.isEmpty) return TaskChanges() to null
    var current = all
    var total = TaskChanges()
    fun step(change: TaskChanges) { current = change.applyTo(current); total += change }
    add.fromTemplate?.let { templateId ->
        val (made, id) = planUseTemplate(all, contexts, edges, templateId, now) ?: return TaskChanges() to null
        step(made)
        val typed = add.task!!
        step(TaskChanges(updates = listOf(current.single { it.id == id }.copy(title = typed.title, startDate = typed.startDate, dueDate = typed.dueDate).withRules(now))))
        if (add.focus) step(TaskChanges(updates = CurrentTask.focus(current, id, now)))
        else if (add.pin) step(TaskChanges(updates = CurrentTask.pin(current, id, now)))
        return total to id
    }
    fun items(parent: Long) = step(planAddItems(current, parent, add.items, now, plan))
    add.intoChecklist?.let { id -> items(id); return total to id }
    val task = add.task ?: return TaskChanges() to null
    val created = planCreate(current, task.copy(parentId = task.parentId ?: defaultParent, isStarred = task.isStarred || (star && !task.isMaybe)), add.contextIds, now)
    step(created)
    val id = created.creates.single().first.id
    items(id)
    add.sections.forEach { (name, sectionItems) -> step(planAddSection(current, id, name, sectionItems, now, plan)) }
    if (add.focus) step(TaskChanges(updates = CurrentTask.focus(current, id, now)))
    else if (add.pin) step(TaskChanges(updates = CurrentTask.pin(current, id, now)))
    return total to id
}

// "Today only" tasks past their day go, with everything under them (deleted, not kept as done).
fun planPurgeExpired(all: List<Task>, now: Long): TaskChanges =
    TaskChanges(deletes = all.filter { it.isExpired(now) }.flatMap { t -> descendants(t.id, all).map { it.id } + t.id }.toSet())

// A checklist's (or one section's) checked items are gone for good (after asking): a groceries list would
// otherwise fill up.
fun planClearChecked(all: List<Task>, checklistId: Long): TaskChanges =
    TaskChanges(deletes = itemsIn(checklistId, all).filter { it.isComplete }.flatMap { t -> descendants(t.id, all).map { it.id } + t.id }.toSet())

// Unchecks every item of a checklist (or one section), each as reopening it would (a repeating item's
// untouched next one is taken back).
fun planUncheckAll(all: List<Task>, checklistId: Long): TaskChanges =
    planEach(all, itemsIn(checklistId, all).filter { it.isComplete }.map { it.id }) { current, id -> planUncomplete(current, id) }

// "Check all in Clothes": every open item of a section (or checklist), each completed as ticking it would.
fun planCheckAll(all: List<Task>, contexts: Map<Long, Set<Long>>, containerId: Long, now: Long): TaskChanges =
    planEach(all, itemsIn(containerId, all).filter { !it.isComplete }.map { it.id }) { current, id -> planComplete(current, contexts, id, now) }

// The answer to completeQuestion: everything under it completes too, or (moveOut) its open subtasks move
// out, and a checklist's unchecked items move to a new copy of it.
fun planCompleteChoosing(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, moveOut: Boolean, now: Long): TaskChanges = when {
    all.firstOrNull { it.id == id }?.type == TaskType.CHECKLIST -> planCompleteChecklist(all, contexts, id, moveUncheckedToNewList = moveOut, now)
    moveOut -> planCompleteAndPromote(all, contexts, id, now)
    else -> planCompleteWithDescendants(all, contexts, id, now)
}

// "Move subtasks out" when completing: they move out (planPromoteChildren), then the task is completed.
fun planCompleteAndPromote(all: List<Task>, contexts: Map<Long, Set<Long>>, id: Long, now: Long): TaskChanges {
    val promote = planPromoteChildren(all, id)
    return promote + planComplete(promote.applyTo(all), contexts, id, now)
}

// Only the direct children move out, to the folder the task sits in (at the end; no folder: the top level),
// as the editor's "Move out" does, so they survive as tasks of their own.
fun planPromoteChildren(all: List<Task>, id: Long): TaskChanges {
    val byId = all.associateBy { it.id }
    val folder = byId[id]?.let { moveOutFolderId(it, byId) }
    return TaskChanges(updates = all.filter { it.parentId == id }.map { it.copy(parentId = folder, position = null) })
}
