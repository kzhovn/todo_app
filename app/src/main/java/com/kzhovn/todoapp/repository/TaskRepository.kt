package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.stillWaiting
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.searchTasks
import com.kzhovn.todoapp.data.planMoveNextTo
import com.kzhovn.todoapp.data.planMoveItem
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.folderColorAssignments
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.completeQuestion
import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.canDependOn
import com.kzhovn.todoapp.data.canMoveUnder
import com.kzhovn.todoapp.data.safeDependencies
import com.kzhovn.todoapp.data.safeParent
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.data.isTimedWait
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.withMoreWait
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.quickadd.planQuickAdd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TaskRepository(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler,
    private val taskContextDao: TaskContextDao,
    // Settings' day rollover, for quick add's "today only" (d:) in checklist items.
    private val rolloverHour: () -> Int = { DEFAULT_ROLLOVER_HOUR },
    // Runs a change as one database transaction (TodoApp passes Room's withTransaction), so a crash or a
    // sync in the middle never sees half of it.
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {

    // What a delete removed, so Undo can put it all back: the tasks and their own contexts and prerequisites.
    data class Deleted(val tasks: List<Task>, val contexts: List<TaskContextCrossRef>, val dependencies: List<TaskDependency>)

    private val _lastDeleted = MutableStateFlow<Deleted?>(null)
    val lastDeleted: StateFlow<Deleted?> = _lastDeleted

    suspend fun <R> atomically(block: suspend () -> R): R {
        var result: Any? = null
        transaction { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    // See planCreate (shared with the server).
    suspend fun createTask(task: Task): Long = atomically {
        val now = System.currentTimeMillis()
        val change = planCreate(taskDao.getAllOnce(), task, now = now)
        apply(change, now)
        change.creates.single().first.id
    }

    suspend fun countDescendants(taskId: Long): Int = taskDao.getDescendants(taskId).size

    // Cascades: deleting a task also deletes every descendant (the confirmation dialog shows how many), except
    // templates in a folder, which move to the top level (templatesKeptFrom). What went is kept in lastDeleted,
    // for Undo.
    suspend fun deleteTask(task: Task) = atomically {
        val stored = taskDao.getById(task.id) ?: return@atomically
        val going = taskDao.getDescendants(task.id) + stored
        val (moved, kept) = templatesKeptFrom(going, taskDao.getAllOnce())
        moved.forEach { write(it) }
        offerUndo(remove(going.filter { it.id !in kept }))
    }

    // Puts a delete back with the original ids, so the subtree's parent links hold as they were.
    suspend fun undoDelete(deleted: Deleted) = atomically {
        deleted.tasks.forEach { t ->
            taskDao.insert(t)
            reminderScheduler.schedule(t)
        }
        deleted.contexts.forEach { taskContextDao.assignContext(it) }
        deleted.dependencies.forEach { taskDao.insertDependency(it) }
        _lastDeleted.value = null
    }

    private fun offerUndo(deleted: Deleted) {
        if (deleted.tasks.isNotEmpty()) _lastDeleted.value = deleted
    }

    // Deletes the tasks with their own context and prerequisite rows (which would otherwise linger), and
    // their alarms. Returns what went.
    private suspend fun remove(tasks: List<Task>): Deleted {
        val contexts = tasks.flatMap { t -> taskContextDao.getContextIdsForTask(t.id).map { TaskContextCrossRef(t.id, it) } }
        val dependencies = tasks.flatMap { t -> taskDao.getDependencyIds(t.id).map { TaskDependency(t.id, it) } }
        tasks.forEach { t ->
            taskDao.deleteById(t.id)
            taskDao.deleteDependenciesOf(t.id)
            taskContextDao.deleteAssignmentsForTask(t.id)
            reminderScheduler.cancel(t)
        }
        return Deleted(tasks, contexts, dependencies)
    }

    fun clearLastDeleted() {
        _lastDeleted.value = null
    }

    // Lands at the end of its new sibling list (a stale position from the old list would misplace it).
    // Refused if it would put the task inside itself, as on the server.
    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
        if (newParentId != null && !canMoveUnder(taskId, newParentId, taskDao.getAllOnce().associateBy { it.id })) return
        write(task.copy(parentId = newParentId, position = null))
    }

    // See planMoveItem (shared with the server): planMoveNextTo, and into a checklist's sections.
    suspend fun moveNextTo(taskId: Long, anchorId: Long, after: Boolean) = atomically {
        val all = taskDao.getAllOnce()
        val move = planMoveItem(all, taskId, anchorId, after) ?: return@atomically
        all.forEach { t -> move.positions[t.id]?.let { write(t.copy(parentId = move.parentId, position = it)) } }
    }

    suspend fun getDescendants(taskId: Long): List<Task> = taskDao.getDescendants(taskId)

    // What "Update subtasks too?" asks about (see overridingSubtasks).
    suspend fun overridingSubtasks(taskId: Long, changed: Set<InheritedField>): Pair<List<Task>, Set<InheritedField>> =
        if (changed.isEmpty()) emptyList<Task>() to emptySet() else overridingSubtasks(taskDao.getDescendants(taskId), getAllTaskContexts(), changed)

    // Clears those fields on the given tasks (as stored now), so they inherit from their ancestors again.
    suspend fun clearInherited(tasks: List<Task>, fields: Set<InheritedField>) = atomically {
        for (t in tasks) {
            write(taskDao.getById(t.id)?.clearInherited(fields) ?: continue)
            if (InheritedField.CONTEXTS in fields) taskContextDao.deleteAssignmentsForTask(t.id)
        }
    }

    // See Task.toggledStar.
    suspend fun toggleStar(taskId: Long) {
        write(taskDao.getById(taskId)?.toggledStar() ?: return)
    }

    // A repeating task's "skip this time": on to its next time, not done (RecurrenceEngine.skip).
    suspend fun skip(taskId: Long, now: Long) {
        write(taskDao.getById(taskId)?.let { RecurrenceEngine.skip(it, now) } ?: return, now)
    }

    suspend fun snooze(taskId: Long, until: Long, now: Long) {
        write(taskDao.getById(taskId)?.copy(startDate = until) ?: return, now)
    }

    // "Still waiting": hidden until the waiting item's next check-in.
    suspend fun stillWaiting(taskId: Long, now: Long) {
        write(taskDao.getById(taskId)?.takeIf { it.type == TaskType.WAITING }?.let { stillWaiting(it, now) } ?: return, now)
    }

    // A running wait's "+10 min" (Doing's Running line, its notification).
    suspend fun moreWait(taskId: Long, now: Long) {
        write(taskDao.getById(taskId)?.takeIf { it.isTimedWait && !it.isComplete }?.withMoreWait(Labels.MORE_WAIT_MINUTES, now) ?: return, now)
    }

    // Every change to an existing task goes through here, as the server's TaskService.update: the write
    // rules (Task.withRules), then the row and its reminder alarms.
    private suspend fun write(task: Task, now: Long = System.currentTimeMillis()) {
        val stored = task.withRules(now)
        taskDao.update(stored)
        reminderScheduler.schedule(stored, now)
    }

    // Carries out a change planned in :core (see TaskChanges), keeping the reminder alarms in step. What's made
    // or moved under a template becomes part of it (intoTemplates). Returns what it deleted.
    private suspend fun apply(planned: TaskChanges, now: Long): Deleted = atomically {
        val changes = planned.intoTemplates(taskDao.getTemplateIds().toSet())
        for ((task, contextIds) in changes.creates) {
            taskDao.insert(task)
            contextIds.forEach { taskContextDao.assignContext(TaskContextCrossRef(task.id, it)) }
            reminderScheduler.schedule(task, now)
        }
        changes.links.forEach { taskDao.insertDependency(it) }
        changes.updates.forEach { write(it, now) }
        remove(changes.deletes.mapNotNull { taskDao.getById(it) })
    }

    // See planComplete (shared with the server).
    suspend fun markComplete(taskId: Long, now: Long) {
        atomically { apply(planComplete(taskDao.getAllOnce(), getAllTaskContexts(), taskId, now), now) }
    }

    suspend fun toggleComplete(taskId: Long, now: Long) = atomically {
        val task = taskDao.getById(taskId) ?: return@atomically
        if (task.isComplete) apply(planUncomplete(taskDao.getAllOnce(), taskId), now) else markComplete(taskId, now)
    }

    // Active, reading everything itself (the tests' entry; screens already hold the tasks and use getActiveTasksFrom).
    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int = minuteOfDay(now), todayMask: Int = dayOfWeekMask(now)): List<Task> =
        getActiveTasksFrom(taskDao.getListedOnce(), getAllTaskContexts(), now, currentMinuteOfDay, todayMask)

    suspend fun getActiveTasksFrom(
        all: List<Task>,
        contextsByTaskId: Map<Long, Set<Long>>,
        now: Long,
        currentMinuteOfDay: Int = minuteOfDay(now),
        todayMask: Int = dayOfWeekMask(now)
    ): List<Task> = computeActiveTasks(
        all, contextsByTaskId, taskContextDao.getAll(), taskContextDao.getAllTimeWindows(),
        taskDao.getAllDependencies(), now, currentMinuteOfDay, todayMask
    )

    // What the lists see: every task but templates (see Templates.kt in :core).
    suspend fun getAllTasks(): List<Task> = taskDao.getListedOnce()

    // Templates included: what an editor open on a template works with.
    suspend fun getEverything(): List<Task> = taskDao.getAllOnce()

    // Templates (see Templates.kt in :core): the list of them, saving one, using one (returns the new list's
    // id), and what a list made from one has changed, to send back.
    suspend fun getTemplates(): List<Task> = templateRoots(taskDao.getTemplatesOnce())

    suspend fun saveAsTemplate(taskId: Long): Long? = atomically {
        val (change, id) = planSaveAsTemplate(taskDao.getAllOnce(), getAllTaskContexts(), taskDao.getAllDependencies(), taskId) ?: return@atomically null
        apply(change, System.currentTimeMillis())
        id
    }

    suspend fun useTemplate(templateId: Long, now: Long): Long? = atomically {
        val (change, id) = planUseTemplate(taskDao.getAllOnce(), getAllTaskContexts(), taskDao.getAllDependencies(), templateId, now) ?: return@atomically null
        apply(change, now)
        id
    }

    // A workflow (see Workflows.kt in :core) starts when made from its template, so it's shown in Doing, not opened.
    suspend fun isWorkflow(id: Long): Boolean = taskDao.getAllOnce().let { all -> isWorkflow(all.firstOrNull { it.id == id }, all) }

    suspend fun templateChanges(listId: Long): List<TemplateChange> = templateChanges(taskDao.getAllOnce(), listId)

    suspend fun updateTemplate(listId: Long, chosen: Set<String>, oneOff: Set<String>) = atomically {
        val now = System.currentTimeMillis()
        apply(planUpdateTemplate(taskDao.getAllOnce(), getAllTaskContexts(), listId, chosen, oneOff, now), now)
    }

    // What the lists and the widget show: every task but templates, after purgeExpired and with a colour slot
    // stored for each folder without one (folderColorAssignments). One read of the table when neither has
    // anything to do, as on most loads.
    suspend fun listedTasks(now: Long): List<Task> {
        val listed = atomically { taskDao.getAllOnce().let { if (purgeExpired(it, now)) taskDao.getAllOnce() else it } }.filter { !it.isTemplate }
        val colors = folderColorAssignments(listed)
        if (colors.isEmpty()) return listed
        atomically { colors.forEach { write(it) } }
        return taskDao.getListedOnce()
    }

    suspend fun getAllTaskContexts(): Map<Long, Set<Long>> =
        taskContextDao.getAllCrossRefs().groupBy({ it.taskId }, { it.contextId }).mapValues { it.value.toSet() }

    suspend fun getFolders(): List<Task> = getAllTasks().filter { it.type == TaskType.FOLDER }

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    // The current task: the pin, with its timer and focus session (see CurrentTask), shared by every device.
    private suspend fun current(change: (List<Task>) -> List<Task>) = atomically { change(taskDao.getAllOnce()).forEach { write(it) } }

    suspend fun pin(taskId: Long, now: Long) = current { CurrentTask.pin(it, taskId, now) }
    suspend fun unpin() = current { CurrentTask.unpin(it) }
    suspend fun startTimer(taskId: Long, minutes: Int, now: Long) = current { CurrentTask.startTimer(it, taskId, minutes, now) }
    suspend fun pauseTimer(now: Long) = current { CurrentTask.pauseTimer(it, now) }
    suspend fun resumeTimer(now: Long) = current { CurrentTask.resumeTimer(it, now) }
    suspend fun addTime(minutes: Int, now: Long) = current { CurrentTask.addTime(it, minutes, now) }
    suspend fun focus(taskId: Long, now: Long) = current { CurrentTask.focus(it, taskId, now) }

    suspend fun getPinnedTask(): Task? = taskDao.getPinned()

    suspend fun getFocusSession(): Task? = taskDao.getFocusSession()

    // An editor's save: a new parent is checked (safeParent) rather than trusted, as on the server.
    suspend fun updateTask(task: Task) {
        val stored = taskDao.getById(task.id)
        val parentId = if (stored == null || task.parentId == stored.parentId) task.parentId
            else safeParent(task, stored.parentId, taskDao.getAllOnce().associateBy { it.id })
        write(task.copy(parentId = parentId))
    }

    suspend fun removeDependency(taskId: Long, dependsOnTaskId: Long) = taskDao.removeDependency(taskId, dependsOnTaskId)

    // Refused if it would make a loop, as on the server.
    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) {
        if (!canDependOn(taskId, dependsOnTaskId, taskDao.getAllDependencies())) return
        taskDao.insertDependency(TaskDependency(taskId, dependsOnTaskId))
    }

    suspend fun getDependencyIds(taskId: Long): Set<Long> = taskDao.getDependencyIds(taskId).toSet()

    // Only links that safeDependencies allows are kept, as on the server.
    suspend fun setDependencies(taskId: Long, dependsOnIds: Set<Long>) = atomically {
        val all = taskDao.getAllOnce().associateBy { it.id }
        val task = all[taskId] ?: return@atomically
        val wanted = safeDependencies(task, dependsOnIds, all, taskDao.getAllDependencies())
        val current = taskDao.getDependencyIds(taskId).toSet()
        (wanted - current).forEach { taskDao.insertDependency(TaskDependency(taskId, it)) }
        (current - wanted).forEach { taskDao.removeDependency(taskId, it) }
    }

    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): List<Task> =
        searchTasks(taskDao.getListedOnce(), getAllTaskContexts(), query, filters)

    suspend fun getAllDependencyEdges(): List<TaskDependency> = taskDao.getAllDependencies()

    suspend fun countActiveDescendants(taskId: Long): Int = openDescendants(taskId, taskDao.getAllOnce()).size

    // Checklist items: "milk, eggs" adds two, in order, at the end of the list.
    // See planAddItems (shared with the server): each item is read with quick add's syntax.
    suspend fun addItems(checklistId: Long, text: String) {
        val now = System.currentTimeMillis()
        atomically { taskDao.getAllOnce().let { all -> apply(planAddItems(all, checklistId, splitItems(text), now, itemPlanner(all, now)), now) } }
    }

    // A subtask typed with quick add's whole syntax (@contexts, a repeat, a note...), under `parentId` whatever
    // the text says, as the server's addTyped does. Under a checklist the text is its items; items into another
    // checklist ("groceries: milk") go there instead.
    suspend fun addSubtask(parentId: Long, text: String) {
        if (taskDao.getById(parentId)?.type == TaskType.CHECKLIST) return addItems(parentId, text)
        val now = System.currentTimeMillis()
        val add = planQuickAdd(text, rolloverHour(), now)
        quickAdd(add.copy(task = add.task?.copy(parentId = parentId)), defaultParent = null, now)
    }

    // Reads each item with quick add's syntax, against the tasks already read for the change.
    private suspend fun itemPlanner(all: List<Task>, now: Long): (String) -> QuickAdd {
        val contexts = taskContextDao.getAll()
        return { planQuickAdd(it, all, contexts, now, rolloverHour()) }
    }

    suspend fun planQuickAdd(text: String, rolloverHour: Int, now: Long): QuickAdd =
        planQuickAdd(text, taskDao.getAllOnce(), taskContextDao.getAll(), now, rolloverHour)

    // See planQuickAddWrite (shared with the server). Returns the task's id (the checklist's, for items).
    suspend fun quickAdd(add: QuickAdd, defaultParent: Long?, now: Long): Long? = atomically {
        val all = taskDao.getAllOnce()
        val (change, id) = planQuickAddWrite(all, add, defaultParent, now, itemPlanner(all, now), contexts = getAllTaskContexts(), edges = taskDao.getAllDependencies())
        apply(change, now)
        id
    }

    // See planClearChecked and planUncheckAll (shared with the server). Cleared items can be brought back
    // with Undo, like a delete.
    suspend fun clearChecked(checklistId: Long) = atomically {
        offerUndo(apply(planClearChecked(taskDao.getAllOnce(), checklistId), System.currentTimeMillis()))
    }

    suspend fun uncheckAll(checklistId: Long) {
        atomically { apply(planUncheckAll(taskDao.getAllOnce(), checklistId), System.currentTimeMillis()) }
    }

    // A section's "Check all" (planCheckAll), and a new section at the end of a checklist (planAddSection).
    suspend fun checkAll(containerId: Long) {
        val now = System.currentTimeMillis()
        atomically { apply(planCheckAll(taskDao.getAllOnce(), getAllTaskContexts(), containerId, now), now) }
    }

    suspend fun addSection(checklistId: Long, title: String) {
        val now = System.currentTimeMillis()
        atomically { taskDao.getAllOnce().let { all -> apply(planAddSection(all, checklistId, title.trim(), emptyList(), now, itemPlanner(all, now)), now) } }
    }

    // See planCompleteChecklist and planCompleteWithDescendants (shared with the server).
    suspend fun completeChecklist(checklistId: Long, moveUncheckedToNewList: Boolean, now: Long) {
        atomically { apply(planCompleteChecklist(taskDao.getAllOnce(), getAllTaskContexts(), checklistId, moveUncheckedToNewList, now), now) }
    }

    suspend fun completeWithDescendants(taskId: Long, now: Long) {
        atomically { apply(planCompleteWithDescendants(taskDao.getAllOnce(), getAllTaskContexts(), taskId, now), now) }
    }

    // Only tasks and checklists (isDoable): none of the bulk properties apply to folders or projects. A move or dependency that
    // would create a cycle is skipped for that task rather than failing the whole edit.
    suspend fun applyBulkEdit(taskIds: Collection<Long>, edit: BulkEdit) = atomically {
        val allById = taskDao.getAllOnce().associateBy { it.id }
        val edges = taskDao.getAllDependencies()
        for (id in taskIds) {
            val task = allById[id]?.takeIf { it.type.isDoable } ?: continue
            write(edit.applyTo(task, allById))
            if (edit.addContextIds.isNotEmpty() || edit.removeContextIds.isNotEmpty()) {
                val current = taskContextDao.getContextIdsForTask(id).toSet()
                (edit.addContextIds - current).forEach { taskContextDao.assignContext(TaskContextCrossRef(id, it)) }
                (edit.removeContextIds intersect current).forEach { taskContextDao.unassignContext(id, it) }
            }
            edit.blockerFor(id, edges)?.let { taskDao.insertDependency(TaskDependency(id, it)) }
        }
    }

    // See planResolveWaiting and planPurgeExpired. Bypasses deleteTask so it doesn't offer an undo for
    // something the user didn't just do.
    suspend fun purgeExpired(now: Long) {
        atomically { purgeExpired(taskDao.getAllOnce(), now) }
    }

    // Over every task, as just read; true when it changed any.
    private suspend fun purgeExpired(all: List<Task>, now: Long): Boolean {
        val resolve = planResolveWaiting(all, getAllTaskContexts(), now)
        val resolved = if (resolve != TaskChanges()) { apply(resolve, now); taskDao.getAllOnce() } else all
        val purge = planPurgeExpired(resolved, now)
        if (purge != TaskChanges()) apply(purge, now)
        return resolved !== all || purge != TaskChanges()
    }

    // See planCompleteAndPromote (shared with the server).
    // See planCompleteChoosing: the answer to completeQuestion.
    suspend fun completeChoosing(taskId: Long, moveOut: Boolean, now: Long) {
        atomically { apply(planCompleteChoosing(taskDao.getAllOnce(), getAllTaskContexts(), taskId, moveOut, now), now) }
    }

    // The question completing it asks first, or null when it has no open subtasks (see completeQuestion).
    suspend fun completeQuestion(taskId: Long): CompleteQuestion? = getTask(taskId)?.let { completeQuestion(it, countActiveDescendants(taskId)) }

    suspend fun completeAndPromote(taskId: Long, now: Long) {
        atomically { apply(planCompleteAndPromote(taskDao.getAllOnce(), getAllTaskContexts(), taskId, now), now) }
    }
}

