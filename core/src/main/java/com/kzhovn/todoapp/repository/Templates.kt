package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.isContainer
import com.kzhovn.todoapp.data.isUnder
import com.kzhovn.todoapp.data.nearestFolder
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.newTaskPositions

// Templates (docs/superpowers/specs/2026-10-09-templates-design.md): the kept version of a packing list, say,
// out of every list until it's used. A template is tasks with isTemplate set, on its root and everything under
// it; each side's storage reads the lists without them (the phone's TaskDao.getListedOnce, the server's
// tasks()) and plans changes against everything. A list made from one carries fromTemplateId on each task,
// which pairs it with the template task it was copied from, for comparing the two (templateChanges).

// The templates themselves (their tops), by title.
fun templateRoots(all: Collection<Task>): List<Task> {
    val byId = all.associateBy { it.id }
    return all.filter { it.isTemplate && byId[it.parentId]?.isTemplate != true }.sortedBy { it.title.lowercase() }
}

// The template a template task belongs to (its top), or null for a task that isn't in one.
fun templateRootOf(task: Task, byId: Map<Long, Task>): Task? {
    if (!task.isTemplate) return null
    var t = task
    val seen = HashSet<Long>()
    while (seen.add(t.id)) t = byId[t.parentId]?.takeIf { it.isTemplate } ?: return t
    return t
}

// What's made or moved under a template becomes part of it: an item added to the Packing template, a task
// moved into it. Applied by each side's storage to every change, with the ids of the template tasks it has.
fun TaskChanges.intoTemplates(templateIds: Set<Long>): TaskChanges {
    val inTemplate = templateIds.toMutableSet()
    val creates = creates.map { (task, contexts) ->
        (if (!task.isTemplate && task.parentId in inTemplate) task.copy(isTemplate = true) else task).also { if (it.isTemplate) inTemplate += it.id } to contexts
    }
    val updates = updates.map { if (!it.isTemplate && it.parentId in inTemplate) it.copy(isTemplate = true) else it }
    return copy(creates = creates, updates = updates)
}

// A copy of a task and everything under it, with new ids, parents and dependencies among them remapped.
// `fresh` makes each copy what it should be (a template, or a new list's task).
private fun planCopy(all: List<Task>, contexts: Map<Long, Set<Long>>, edges: List<TaskDependency>, rootId: Long, fresh: (Task, Task) -> Task): Pair<TaskChanges, Long>? {
    val byId = all.associateBy { it.id }
    val root = byId[rootId] ?: return null
    val subtree = listOf(root) + all.filter { isUnder(it, rootId, byId) }.sortedBy { depth(it, rootId, byId) }
    val newIds = subtree.associate { it.id to newId() }
    val copies = subtree.map { original ->
        fresh(original, original.copy(id = newIds.getValue(original.id), parentId = if (original.id == rootId) original.parentId else newIds[original.parentId]))
    }
    val links = edges.filter { it.taskId in newIds && it.dependsOnTaskId in newIds }.map { TaskDependency(newIds.getValue(it.taskId), newIds.getValue(it.dependsOnTaskId)) }
    return TaskChanges(creates = copies.zip(subtree) { copy, original -> copy to contexts[original.id].orEmpty() }, links = links) to newIds.getValue(rootId)
}

private fun depth(task: Task, rootId: Long, byId: Map<Long, Task>): Int {
    var d = 0
    var t: Task? = task
    while (t != null && t.id != rootId && d < 100) { t = byId[t.parentId]; d++ }
    return d
}

// What a template keeps of a task, and what a list made from it starts with: no dates, nothing done, not
// pinned or focused or starred, nothing "today only" (a packing list's dates belong to each trip).
private fun Task.asFresh(): Task = copy(
    startDate = null, dueDate = null, isComplete = false, completedAt = null, isStarred = false, pinnedAt = null,
    timerEndsAt = null, timerRemaining = null, focusedAt = null, expiresAt = null, remindAt = null, maybeSince = null
)

// "Save as template": a template copy of the task and everything under it; the task carries on as it was.
// Its top goes in the task's folder (never under another task, whose open subtasks it would otherwise count as).
fun planSaveAsTemplate(all: List<Task>, contexts: Map<Long, Set<Long>>, edges: List<TaskDependency>, taskId: Long): Pair<TaskChanges, Long>? {
    val byId = all.associateBy { it.id }
    val folder = byId[taskId]?.let { t -> if (t.type == TaskType.FOLDER) t.parentId else nearestFolder(t, byId)?.id }
    return planCopy(all, contexts, edges, taskId) { original, copy ->
        copy.asFresh().copy(isTemplate = true, fromTemplateId = null, parentId = if (original.id == taskId) folder else copy.parentId, position = if (original.id == taskId) null else copy.position)
    }
}

// Using a template: a new list from it, in the template's folder (first there, as a new task goes), each task
// linked back to the template task it came from. Returns the new list's id.
fun planUseTemplate(all: List<Task>, contexts: Map<Long, Set<Long>>, edges: List<TaskDependency>, templateId: Long, now: Long): Pair<TaskChanges, Long>? {
    val (copy, rootId) = planCopy(all, contexts, edges, templateId) { original, c -> c.asFresh().copy(isTemplate = false, fromTemplateId = original.id) } ?: return null
    val root = copy.creates.first { it.first.id == rootId }.first
    val positions = newTaskPositions(all.filter { !it.isTemplate }, root.copy(position = null))
    val placed = copy.copy(creates = copy.creates.map { (t, c) -> (if (t.id == rootId) t.copy(position = positions[rootId]) else t).withRules(now) to c })
    return placed + TaskChanges(updates = all.mapNotNull { t -> positions[t.id]?.takeIf { t.id != rootId }?.let { t.copy(position = it) } }) to rootId
}

// How a list made from a template differs from it, for "Update template…": what was added to the list, taken
// out of it, renamed (or otherwise changed: a maybe, priority, timer, note) or moved to another section. The
// list's own top isn't compared (it's renamed for each use: "Packing: Lisbon").
sealed interface TemplateChange {
    val key: String // stable across requests, for the review's checkboxes
    data class Added(val task: Task, val intoTemplateId: Long) : TemplateChange { override val key = "add-${task.id}" }
    data class Removed(val templateTask: Task) : TemplateChange { override val key = "remove-${templateTask.id}" }
    data class Changed(val task: Task, val templateTask: Task) : TemplateChange { override val key = "change-${task.id}" }
    data class Moved(val task: Task, val templateTask: Task, val toTemplateId: Long) : TemplateChange { override val key = "move-${task.id}" }
}

// The template a list was made from (its top), if it's still there.
fun templateOf(list: Task, byId: Map<Long, Task>): Task? = list.fromTemplateId?.let(byId::get)?.takeIf { it.isTemplate }

fun templateChanges(all: List<Task>, listId: Long): List<TemplateChange> {
    val byId = all.associateBy { it.id }
    val list = byId[listId] ?: return emptyList()
    val template = templateOf(list, byId) ?: return emptyList()
    val inTemplate = all.filter { isUnder(it, template.id, byId) }
    val inList = all.filter { isUnder(it, listId, byId) }
    val templateIds = inTemplate.map { it.id }.toSet() + template.id
    // Where a list task's parent sits in the template: the template's top for the list's own top.
    fun templateParent(t: Task): Long? = if (t.parentId == listId) template.id else byId[t.parentId]?.fromTemplateId?.takeIf { it in templateIds }
    val paired = inList.filter { it.fromTemplateId in templateIds }.associateBy { it.fromTemplateId!! }
    val changes = mutableListOf<TemplateChange>()
    inList.forEach { t ->
        val from = t.fromTemplateId?.let(byId::get)?.takeIf { it.id in templateIds }
        when {
            t.fromTemplateId == JUST_THIS_TIME -> {}
            from == null -> templateParent(t)?.let { changes += TemplateChange.Added(t, it) }
            else -> {
                if (differs(t, from)) changes += TemplateChange.Changed(t, from)
                templateParent(t)?.takeIf { it != from.parentId }?.let { changes += TemplateChange.Moved(t, from, it) }
            }
        }
    }
    inTemplate.filter { it.id !in paired }.forEach { changes += TemplateChange.Removed(it) }
    return changes
}

// fromTemplateId on a task added "just this time": never offered to the template.
const val JUST_THIS_TIME = 0L

private fun differs(t: Task, from: Task) = t.title != from.title || t.isMaybe != from.isMaybe || t.isHighPriority != from.isHighPriority ||
    t.durationMinutes != from.durationMinutes || t.notes.orEmpty() != from.notes.orEmpty() || t.type != from.type

// Writes the chosen differences into the template; `oneOff` ones (added items) are marked "just this time" so
// they aren't offered again. An added task is copied into the template and then paired with it.
fun planUpdateTemplate(all: List<Task>, contexts: Map<Long, Set<Long>>, listId: Long, chosen: Set<String>, oneOff: Set<String>, now: Long): TaskChanges {
    val changes = templateChanges(all, listId)
    var total = TaskChanges()
    val newIds = mutableMapOf<Long, Long>() // list task -> its new template copy, for added tasks nested in added ones
    changes.filter { it.key in oneOff && it is TemplateChange.Added }.forEach { total += TaskChanges(updates = listOf((it as TemplateChange.Added).task.copy(fromTemplateId = JUST_THIS_TIME))) }
    changes.filter { it.key in chosen }.forEach { change ->
        total += when (change) {
            is TemplateChange.Added -> {
                val parent = newIds[change.task.parentId] ?: change.intoTemplateId
                val copy = change.task.asFresh().copy(id = newId(), parentId = parent, isTemplate = true, fromTemplateId = null).withRules(now)
                newIds[change.task.id] = copy.id
                TaskChanges(creates = listOf(copy to contexts[change.task.id].orEmpty()), updates = listOf(change.task.copy(fromTemplateId = copy.id)))
            }
            is TemplateChange.Removed -> TaskChanges(deletes = all.filter { isUnder(it, change.templateTask.id, all.associateBy { t -> t.id }) }.map { it.id }.toSet() + change.templateTask.id)
            is TemplateChange.Changed -> TaskChanges(updates = listOf(change.templateTask.copy(
                title = change.task.title, isMaybe = change.task.isMaybe, isHighPriority = change.task.isHighPriority,
                durationMinutes = change.task.durationMinutes, notes = change.task.notes, type = change.task.type
            ).withRules(now)))
            is TemplateChange.Moved -> TaskChanges(updates = listOf(change.templateTask.copy(parentId = change.toTemplateId, position = null)))
        }
    }
    return total
}

// Deleting a folder keeps the templates in it: they move to the top level (with what's under them) instead.
// Returns the moves, and the ids to leave out of the deletion.
fun templatesKeptFrom(deleting: Collection<Task>, all: Collection<Task>): Pair<List<Task>, Set<Long>> {
    val byId = all.associateBy { it.id }
    val ids = deleting.map { it.id }.toSet()
    val roots = deleting.filter { it.isTemplate && byId[it.parentId]?.isTemplate != true && it.parentId in ids }
    val kept = roots.flatMap { r -> all.filter { isUnder(it, r.id, byId) }.map { it.id } + r.id }.toSet()
    return roots.map { it.copy(parentId = null, position = null) } to kept
}

// A line of the "Update template" review: what changed ("Socks → Socks ×5"), and its tag ("renamed").
fun describe(change: TemplateChange, byId: Map<Long, Task>): Pair<String, String> = when (change) {
    is TemplateChange.Added -> (change.task.title + (byId[change.intoTemplateId]?.takeIf { it.type == TaskType.SECTION }?.let { " (in ${it.title})" } ?: "")) to "added"
    is TemplateChange.Removed -> change.templateTask.title to "removed"
    is TemplateChange.Changed -> (if (change.task.title != change.templateTask.title) "${change.templateTask.title} → ${change.task.title}" else change.task.title + changedWhat(change.task, change.templateTask)) to
        (if (change.task.title != change.templateTask.title) "renamed" else "changed")
    is TemplateChange.Moved -> (change.task.title + " to " + (byId[change.toTemplateId]?.takeIf { it.type == TaskType.SECTION }?.title ?: "the top")) to "moved"
}

private fun changedWhat(t: Task, from: Task): String = listOfNotNull(
    "now a maybe".takeIf { t.isMaybe && !from.isMaybe }, "no longer a maybe".takeIf { !t.isMaybe && from.isMaybe },
    "now high priority".takeIf { t.isHighPriority && !from.isHighPriority }, "timer changed".takeIf { t.durationMinutes != from.durationMinutes },
    "note changed".takeIf { t.notes.orEmpty() != from.notes.orEmpty() }, "type changed".takeIf { t.type != from.type }
).joinToString(", ").let { if (it.isEmpty()) "" else ": $it" }

// What the review starts with ticked: everything but removals (taking out of the template is deliberate).
fun TemplateChange.tickedAtFirst(): Boolean = this !is TemplateChange.Removed

// The number of tasks under a template, for templateSummary.
fun templateSize(template: Task, all: Collection<Task>): Int {
    val byId = all.associateBy { it.id }
    return all.count { !it.type.isContainer && isUnder(it, template.id, byId) }
}
