package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
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
        val toInsert = (if (task.id == 0L) task.copy(id = newId()) else task).starRule()
        taskDao.insert(toInsert)
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

    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
        taskDao.update(task.copy(parentId = newParentId))
    }

    suspend fun toggleStar(taskId: Long) {
        val task = taskDao.getById(taskId)?.takeUnless { it.isMaybe } ?: return
        taskDao.update(task.copy(isStarred = !task.isStarred))
    }

    suspend fun snooze(taskId: Long, durationMillis: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(startDate = now + durationMillis)
        taskDao.update(updated)
        reminderScheduler.schedule(updated, now)
    }

    suspend fun markComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val completedTask = task.copy(isComplete = true, completedAt = now)
        taskDao.update(completedTask)
        reminderScheduler.cancel(completedTask)
        RecurrenceEngine.nextInstance(completedTask, now)?.let {
            taskDao.insert(it)
            reminderScheduler.schedule(it, now)
        }
    }

    suspend fun toggleComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.isComplete) {
            RecurrenceEngine.untouchedSuccessor(task, taskDao.getAllOnce())?.let {
                taskDao.deleteById(it.id)
                reminderScheduler.cancel(it)
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

    suspend fun getAllTaskContexts(): Map<Long, Set<Long>> =
        taskContextDao.getAllCrossRefs().groupBy({ it.taskId }, { it.contextId }).mapValues { it.value.toSet() }

    suspend fun getFolders(): List<Task> = getAllTasks().filter { it.type == TaskType.FOLDER }

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    suspend fun updateTask(task: Task) {
        taskDao.update(task.starRule())
        reminderScheduler.schedule(task)
    }

    suspend fun setDueDate(taskId: Long, dueDate: Long?) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(dueDate = dueDate)
        taskDao.update(updated)
        reminderScheduler.schedule(updated)
    }

    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) =
        taskDao.insertDependency(TaskDependency(taskId, dependsOnTaskId))

    suspend fun getDependencyIds(taskId: Long): Set<Long> = taskDao.getDependencyIds(taskId).toSet()

    suspend fun setDependencies(taskId: Long, dependsOnIds: Set<Long>) {
        val current = taskDao.getDependencyIds(taskId).toSet()
        (dependsOnIds - current).forEach { taskDao.insertDependency(TaskDependency(taskId, it)) }
        (current - dependsOnIds).forEach { taskDao.removeDependency(taskId, it) }
    }

    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): List<Task> =
        taskDao.searchFiltered(
            query = query,
            includeCompleted = filters.includeCompleted,
            folderId = filters.folderId,
            starredOnly = filters.starredOnly,
            dueAfter = filters.dueAfter,
            dueBefore = filters.dueBefore,
            contextId = filters.contextId
        )

    suspend fun getAllDependencyEdges(): List<TaskDependency> = taskDao.getAllDependencies()

    suspend fun countActiveDescendants(taskId: Long): Int =
        taskDao.getDescendants(taskId).count { it.type != TaskType.FOLDER && !it.isComplete }

    // Cascades completion to every active descendant first — each goes through markComplete
    // individually so a recurring descendant still spawns its own next instance.
    suspend fun completeWithDescendants(taskId: Long, now: Long) {
        taskDao.getDescendants(taskId)
            .filter { it.type != TaskType.FOLDER && !it.isComplete }
            .forEach { markComplete(it.id, now) }
        markComplete(taskId, now)
    }

    // Detaches this task's direct children (only direct — any grandchildren stay nested under
    // their own now-top-level parent) so they survive as independent tasks.
    suspend fun promoteChildrenToTopLevel(taskId: Long) {
        taskDao.getChildren(taskId).forEach { child -> taskDao.update(child.copy(parentId = null)) }
    }
}
