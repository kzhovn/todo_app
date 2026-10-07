package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.searchTasks
import com.kzhovn.todoapp.data.planMoveNextTo
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.folderColorAssignments
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.canDependOn
import com.kzhovn.todoapp.data.canMoveUnder
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.quickadd.planQuickAdd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TaskRepository(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler,
    private val taskContextDao: TaskContextDao
) {

    private val _lastDeleted = MutableStateFlow<List<Task>?>(null)
    val lastDeleted: StateFlow<List<Task>?> = _lastDeleted

    // See planCreate (shared with the server).
    suspend fun createTask(task: Task): Long {
        val now = System.currentTimeMillis()
        val change = planCreate(taskDao.getAllOnce(), task, now = now)
        apply(change, now)
        return change.creates.single().first.id
    }

    suspend fun countDescendants(taskId: Long): Int = taskDao.getDescendants(taskId).size

    // Cascades: deleting a folder/task also deletes every descendant, canceling each one's
    // reminder alarm too. The confirmation dialog (TaskEditActivity) shows countDescendants()
    // before the user commits, so this is never a surprise. Snapshots the task + descendants into
    // lastDeleted BEFORE removing anything, so undoDelete can restore the whole subtree.
    suspend fun deleteTask(task: Task) {
        val descendants = taskDao.getDescendants(task.id)
        _lastDeleted.value = descendants + task
        descendants.forEach { child ->
            taskDao.deleteById(child.id)
            reminderScheduler.cancel(child)
        }
        taskDao.delete(task)
        reminderScheduler.cancel(task)
    }

    // Reinserts every task in the snapshot (the originally-deleted task plus all its descendants)
    // with their original ids intact, so parentId relationships between them are preserved exactly
    // as they were.
    suspend fun undoDelete(tasks: List<Task>) {
        tasks.forEach { t ->
            taskDao.insert(t)
            reminderScheduler.schedule(t)
        }
        _lastDeleted.value = null
    }

    fun clearLastDeleted() {
        _lastDeleted.value = null
    }

    // Lands at the end of its new sibling list (a stale position from the old list would misplace it).
    // Refused if it would put the task inside itself, as on the server.
    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
        if (newParentId != null && !canMoveUnder(taskId, newParentId, taskDao.getAllOnce().associateBy { it.id })) return
        taskDao.update(task.copy(parentId = newParentId, position = null))
    }

    // See planMoveNextTo (shared with the server).
    suspend fun moveNextTo(taskId: Long, anchorId: Long, after: Boolean) {
        val all = taskDao.getAllOnce()
        val move = planMoveNextTo(all, taskId, anchorId, after) ?: return
        all.forEach { t -> move.positions[t.id]?.let { taskDao.update(t.copy(parentId = move.parentId, position = it)) } }
    }

    suspend fun getDescendants(taskId: Long): List<Task> = taskDao.getDescendants(taskId)

    // Subtasks that set their own value for one of these inherited fields, and so wouldn't follow
    // a change to it on this task (see overridesInherited).
    suspend fun descendantsOverriding(taskId: Long, fields: Set<InheritedField>): List<Task> =
        taskDao.getDescendants(taskId).filter { d ->
            val contexts = taskContextDao.getContextIdsForTask(d.id).toSet()
            fields.any { overridesInherited(d, contexts, it) }
        }

    // Clears those fields on the given tasks, so they inherit from their ancestors again.
    suspend fun clearInherited(tasks: List<Task>, fields: Set<InheritedField>) {
        for (t in tasks) {
            updateTask(
                t.copy(
                    startDate = t.startDate.takeUnless { InheritedField.START in fields },
                    dueDate = t.dueDate.takeUnless { InheritedField.DUE in fields },
                    icon = t.icon.takeUnless { InheritedField.ICON in fields }
                )
            )
            if (InheritedField.CONTEXTS in fields) taskContextDao.deleteAssignmentsForTask(t.id)
        }
    }

    suspend fun toggleStar(taskId: Long) {
        val task = taskDao.getById(taskId)?.takeUnless { it.isMaybe } ?: return
        taskDao.update(task.copy(isStarred = !task.isStarred))
    }

    // A repeating task's "skip this time": on to its next time, not done (RecurrenceEngine.skip).
    suspend fun skip(taskId: Long, now: Long) {
        val skipped = taskDao.getById(taskId)?.let { RecurrenceEngine.skip(it, now) } ?: return
        taskDao.update(skipped)
        reminderScheduler.schedule(skipped, now)
    }

    suspend fun snooze(taskId: Long, until: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(startDate = until)
        taskDao.update(updated)
        reminderScheduler.schedule(updated, now)
    }

    // Carries out a change planned in :core (see TaskChanges), keeping the reminder alarms in step.
    private suspend fun apply(changes: TaskChanges, now: Long) {
        for ((task, contextIds) in changes.creates) {
            taskDao.insert(task)
            contextIds.forEach { taskContextDao.assignContext(TaskContextCrossRef(task.id, it)) }
            reminderScheduler.schedule(task, now)
        }
        changes.updates.forEach { taskDao.update(it); reminderScheduler.schedule(it, now) }
        changes.deletes.forEach { id -> taskDao.getById(id)?.let { taskDao.deleteById(id); reminderScheduler.cancel(it) } }
    }

    // See planComplete (shared with the server).
    suspend fun markComplete(taskId: Long, now: Long) = apply(planComplete(taskDao.getAllOnce(), getAllTaskContexts(), taskId, now), now)

    suspend fun toggleComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.isComplete) apply(planUncomplete(taskDao.getAllOnce(), taskId), now) else markComplete(taskId, now)
    }

    // Entry point for callers without task/context data in hand. TaskListViewModel and TodoWidget
    // already fetch both for display, so they call getActiveTasksFrom instead of fetching twice.
    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int, todayMask: Int): List<Task> {
        val all = taskDao.getAllOnce()
        return getActiveTasksFrom(all, getAllTaskContexts(), now, currentMinuteOfDay, todayMask)
    }

    suspend fun getActiveTasksFrom(
        all: List<Task>,
        contextsByTaskId: Map<Long, Set<Long>>,
        now: Long,
        currentMinuteOfDay: Int,
        todayMask: Int
    ): List<Task> = computeActiveTasks(
        all, contextsByTaskId, taskContextDao.getAll(), taskContextDao.getAllTimeWindows(),
        taskDao.getAllDependencies(), now, currentMinuteOfDay, todayMask
    )

    suspend fun getAllTasks(): List<Task> = taskDao.getAllOnce()

    // Stores colour slots for folders that don't have one yet (see folderColorAssignments).
    suspend fun ensureFolderColors() = folderColorAssignments(taskDao.getAllOnce()).forEach { taskDao.update(it) }

    suspend fun getAllTaskContexts(): Map<Long, Set<Long>> =
        taskContextDao.getAllCrossRefs().groupBy({ it.taskId }, { it.contextId }).mapValues { it.value.toSet() }

    suspend fun getFolders(): List<Task> = getAllTasks().filter { it.type == TaskType.FOLDER }

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    // The current task: the pin, with its timer and focus session (see CurrentTask), shared by every device.
    private suspend fun current(change: (List<Task>) -> List<Task>) = change(taskDao.getAllOnce()).forEach { taskDao.update(it) }

    suspend fun pin(taskId: Long, now: Long) = current { CurrentTask.pin(it, taskId, now) }
    suspend fun unpin() = current { CurrentTask.unpin(it) }
    suspend fun startTimer(taskId: Long, minutes: Int, now: Long) = current { CurrentTask.startTimer(it, taskId, minutes, now) }
    suspend fun pauseTimer(now: Long) = current { CurrentTask.pauseTimer(it, now) }
    suspend fun resumeTimer(now: Long) = current { CurrentTask.resumeTimer(it, now) }
    suspend fun addTime(minutes: Int, now: Long) = current { CurrentTask.addTime(it, minutes, now) }
    suspend fun focus(taskId: Long, now: Long) = current { CurrentTask.focus(it, taskId, now) }

    suspend fun getPinnedTask(): Task? = taskDao.getPinned()

    suspend fun getFocusSession(): Task? = CurrentTask.focusSession(taskDao.getAllOnce())

    suspend fun updateTask(task: Task) {
        taskDao.update(task.withRules(System.currentTimeMillis()))
        reminderScheduler.schedule(task)
    }

    suspend fun removeDependency(taskId: Long, dependsOnTaskId: Long) = taskDao.removeDependency(taskId, dependsOnTaskId)

    // Refused if it would make a loop, as on the server.
    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) {
        if (!canDependOn(taskId, dependsOnTaskId, taskDao.getAllDependencies())) return
        taskDao.insertDependency(TaskDependency(taskId, dependsOnTaskId))
    }

    suspend fun getDependencyIds(taskId: Long): Set<Long> = taskDao.getDependencyIds(taskId).toSet()

    suspend fun setDependencies(taskId: Long, dependsOnIds: Set<Long>) {
        val current = taskDao.getDependencyIds(taskId).toSet()
        (dependsOnIds - current).forEach { taskDao.insertDependency(TaskDependency(taskId, it)) }
        (current - dependsOnIds).forEach { taskDao.removeDependency(taskId, it) }
    }

    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): List<Task> =
        searchTasks(taskDao.getAllOnce(), getAllTaskContexts(), query, filters)

    suspend fun getAllDependencyEdges(): List<TaskDependency> = taskDao.getAllDependencies()

    suspend fun countActiveDescendants(taskId: Long): Int =
        taskDao.getDescendants(taskId).count { it.type != TaskType.FOLDER && !it.isComplete }

    // Checklist items: "milk, eggs" adds two, in order, at the end of the list.
    suspend fun addItems(checklistId: Long, text: String) = splitItems(text).forEach { createTask(Task(title = it, parentId = checklistId)) }

    suspend fun planQuickAdd(text: String, rolloverHour: Int, now: Long): QuickAdd =
        planQuickAdd(text, taskDao.getAllOnce(), taskContextDao.getAll(), now, rolloverHour)

    // See planQuickAddWrite (shared with the server). Returns the task's id (the checklist's, for items).
    suspend fun quickAdd(add: QuickAdd, defaultParent: Long?, now: Long): Long? {
        val (change, id) = planQuickAddWrite(taskDao.getAllOnce(), add, defaultParent, now)
        apply(change, now)
        return id
    }

    // Checked items are gone for good (after asking): a groceries list would otherwise fill up with them.
    suspend fun clearChecked(checklistId: Long) =
        taskDao.getAllOnce().filter { it.parentId == checklistId && it.isComplete }.forEach { taskDao.deleteById(it.id) }

    suspend fun uncheckAll(checklistId: Long) =
        taskDao.getAllOnce().filter { it.parentId == checklistId && it.isComplete }.forEach { taskDao.update(it.copy(isComplete = false, completedAt = null)) }

    // See planCompleteChecklist and planCompleteWithDescendants (shared with the server).
    suspend fun completeChecklist(checklistId: Long, moveUncheckedToNewList: Boolean, now: Long) =
        apply(planCompleteChecklist(taskDao.getAllOnce(), getAllTaskContexts(), checklistId, moveUncheckedToNewList, now), now)

    suspend fun completeWithDescendants(taskId: Long, now: Long) =
        apply(planCompleteWithDescendants(taskDao.getAllOnce(), getAllTaskContexts(), taskId, now), now)

    // Only tasks and checklists (isDoable): none of the bulk properties apply to folders or projects. A move or dependency that
    // would create a cycle is skipped for that task rather than failing the whole edit.
    suspend fun applyBulkEdit(taskIds: Collection<Long>, edit: BulkEdit) {
        val allById = taskDao.getAllOnce().associateBy { it.id }
        val edges = taskDao.getAllDependencies()
        for (id in taskIds) {
            val task = allById[id]?.takeIf { it.type.isDoable } ?: continue
            updateTask(edit.applyTo(task, allById))
            if (edit.addContextIds.isNotEmpty() || edit.removeContextIds.isNotEmpty()) {
                val current = taskContextDao.getContextIdsForTask(id).toSet()
                (edit.addContextIds - current).forEach { taskContextDao.assignContext(TaskContextCrossRef(id, it)) }
                (edit.removeContextIds intersect current).forEach { taskContextDao.unassignContext(id, it) }
            }
            edit.blockerFor(id, edges)?.let { taskDao.insertDependency(TaskDependency(id, it)) }
        }
    }

    // Deletes "Today only" tasks (and anything under them) once their day has rolled over, and resolves
    // waiting items whose date has come. Bypasses deleteTask so it doesn't offer an undo for something
    // the user didn't just do.
    suspend fun purgeExpired(now: Long) {
        apply(planResolveWaiting(taskDao.getAllOnce(), getAllTaskContexts(), now), now)
        taskDao.getAllOnce().filter { it.isExpired(now) }.forEach { task ->
            (taskDao.getDescendants(task.id) + task).forEach {
                taskDao.deleteById(it.id)
                reminderScheduler.cancel(it)
            }
        }
    }

    // Detaches this task's direct children (only direct — any grandchildren stay nested under
    // their own now-top-level parent) so they survive as independent tasks. Like reparent, they land
    // at the end of the top level (a stale position from the old list would misplace them).
    suspend fun promoteChildrenToTopLevel(taskId: Long) {
        taskDao.getChildren(taskId).forEach { child -> taskDao.update(child.copy(parentId = null, position = null)) }
    }
}

