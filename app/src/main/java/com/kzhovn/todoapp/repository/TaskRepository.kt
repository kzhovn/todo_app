package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.searchTasks
import com.kzhovn.todoapp.data.planMoveNextTo
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.newTaskPositions
import com.kzhovn.todoapp.data.folderColorAssignments
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TaskRepository(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler,
    private val taskContextDao: TaskContextDao
) {

    private val _lastDeleted = MutableStateFlow<List<Task>?>(null)
    val lastDeleted: StateFlow<List<Task>?> = _lastDeleted

    suspend fun createTask(task: Task): Long {
        val created = (if (task.id == 0L) task.copy(id = newId()) else task).withRules(System.currentTimeMillis())
        val all = taskDao.getAllOnce()
        val positions = newTaskPositions(all, created)
        val toInsert = created.copy(position = positions[created.id] ?: created.position)
        taskDao.insert(toInsert)
        all.forEach { t -> positions[t.id]?.let { taskDao.update(t.copy(position = it)) } }
        reminderScheduler.schedule(toInsert)
        return toInsert.id
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
    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
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

    suspend fun snooze(taskId: Long, until: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(startDate = until)
        taskDao.update(updated)
        reminderScheduler.schedule(updated, now)
    }

    suspend fun markComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val completedTask = task.copy(isComplete = true, completedAt = now)
        taskDao.update(completedTask)
        reminderScheduler.cancel(completedTask)
        // The next instance keeps the task's contexts and gets fresh copies of its subtasks.
        RecurrenceEngine.nextInstance(completedTask, now)?.let { next ->
            val copies = listOf(taskId to next) +
                RecurrenceEngine.successorSubtasks(completedTask, next, taskDao.getDescendants(taskId))
            for ((originalId, copy) in copies) {
                taskDao.insert(copy)
                taskContextDao.getContextIdsForTask(originalId).forEach { taskContextDao.assignContext(TaskContextCrossRef(copy.id, it)) }
                reminderScheduler.schedule(copy, now)
            }
        }
    }

    suspend fun toggleComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.isComplete) {
            RecurrenceEngine.untouchedSuccessor(task, taskDao.getAllOnce())?.let { successor ->
                (taskDao.getDescendants(successor.id) + successor).forEach {
                    taskDao.deleteById(it.id)
                    reminderScheduler.cancel(it)
                }
            }
            val reopened = task.copy(isComplete = false, completedAt = null)
            taskDao.update(reopened)
            reminderScheduler.schedule(reopened, now)
        } else {
            markComplete(taskId, now)
        }
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

    suspend fun updateTask(task: Task) {
        taskDao.update(task.withRules(System.currentTimeMillis()))
        reminderScheduler.schedule(task)
    }

    suspend fun removeDependency(taskId: Long, dependsOnTaskId: Long) = taskDao.removeDependency(taskId, dependsOnTaskId)

    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) =
        taskDao.insertDependency(TaskDependency(taskId, dependsOnTaskId))

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

    // Checked items are gone for good (after asking): a groceries list would otherwise fill up with them.
    suspend fun clearChecked(checklistId: Long) =
        taskDao.getAllOnce().filter { it.parentId == checklistId && it.isComplete }.forEach { taskDao.deleteById(it.id) }

    suspend fun uncheckAll(checklistId: Long) =
        taskDao.getAllOnce().filter { it.parentId == checklistId && it.isComplete }.forEach { taskDao.update(it.copy(isComplete = false, completedAt = null)) }

    // Completing a checklist with items still unchecked: they either move to a fresh copy of the
    // list (same place, contexts and star), or are completed along with it.
    suspend fun completeChecklist(checklistId: Long, moveUncheckedToNewList: Boolean, now: Long) {
        val checklist = taskDao.getById(checklistId) ?: return
        val unchecked = taskDao.getAllOnce().filter { it.parentId == checklistId && !it.isComplete }
        if (moveUncheckedToNewList && unchecked.isNotEmpty()) {
            val copy = checklist.copy(id = newId(), position = null, recurrenceType = null, recurrenceRule = null)
            taskDao.insert(copy)
            taskContextDao.getContextIdsForTask(checklistId).forEach { taskContextDao.assignContext(TaskContextCrossRef(copy.id, it)) }
            unchecked.forEach { taskDao.update(it.copy(parentId = copy.id)) }
        }
        completeWithDescendants(checklistId, now)
    }

    // Cascades completion to every active descendant first — each goes through markComplete
    // individually so a recurring descendant still spawns its own next instance.
    suspend fun completeWithDescendants(taskId: Long, now: Long) {
        taskDao.getDescendants(taskId)
            .filter { it.type != TaskType.FOLDER && !it.isComplete }
            .forEach { markComplete(it.id, now) }
        markComplete(taskId, now)
    }

    // Folders are skipped: none of the bulk properties apply to them. A move or dependency that
    // would create a cycle is skipped for that task rather than failing the whole edit.
    suspend fun applyBulkEdit(taskIds: Collection<Long>, edit: BulkEdit) {
        val allById = taskDao.getAllOnce().associateBy { it.id }
        val edges = taskDao.getAllDependencies()
        for (id in taskIds) {
            val task = allById[id]?.takeIf { it.type == TaskType.TASK } ?: continue
            updateTask(edit.applyTo(task, allById))
            if (edit.addContextIds.isNotEmpty() || edit.removeContextIds.isNotEmpty()) {
                val current = taskContextDao.getContextIdsForTask(id).toSet()
                (edit.addContextIds - current).forEach { taskContextDao.assignContext(TaskContextCrossRef(id, it)) }
                (edit.removeContextIds intersect current).forEach { taskContextDao.unassignContext(id, it) }
            }
            edit.blockerFor(id, edges)?.let { taskDao.insertDependency(TaskDependency(id, it)) }
        }
    }

    // Deletes "just for today" tasks (and anything under them) once their day has rolled over.
    // Bypasses deleteTask so it doesn't offer an undo for something the user didn't just do.
    suspend fun purgeExpired(now: Long) {
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

