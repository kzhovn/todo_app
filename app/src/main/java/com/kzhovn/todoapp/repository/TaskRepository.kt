package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

class TaskRepository(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler
) {

    suspend fun createTask(task: Task): Long {
        val id = taskDao.insert(task)
        reminderScheduler.schedule(task.copy(id = id))
        return id
    }

    suspend fun countDescendants(taskId: Long): Int = taskDao.countDescendants(taskId)

    // Cascades: deleting a folder/task also deletes every descendant, canceling each one's
    // reminder alarm too. The confirmation dialog (TaskEditActivity) shows countDescendants()
    // before the user commits, so this is never a surprise.
    suspend fun deleteTask(task: Task) {
        val descendants = taskDao.getDescendants(task.id)
        descendants.forEach { child ->
            taskDao.deleteById(child.id)
            reminderScheduler.cancel(child)
        }
        taskDao.delete(task)
        reminderScheduler.cancel(task)
    }

    suspend fun undoDelete(task: Task) {
        taskDao.insert(task)
        reminderScheduler.schedule(task)
    }

    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
        taskDao.update(task.copy(parentId = newParentId))
    }

    suspend fun toggleStar(taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        taskDao.update(task.copy(isStarred = !task.isStarred))
    }

    suspend fun snooze(taskId: Long, durationMillis: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(startDate = now + durationMillis)
        taskDao.update(updated)
        reminderScheduler.schedule(updated)
    }

    suspend fun markComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val completedTask = task.copy(isComplete = true, completedAt = now)
        taskDao.update(completedTask)
        reminderScheduler.cancel(completedTask)
        RecurrenceEngine.nextInstance(completedTask, now)?.let {
            val nextId = taskDao.insert(it)
            // Only schedule alarm for future due dates; spawned instances inherit the original's
            // dueDate, which is often in the past if the task was completed at/after its due date.
            if (it.dueDate == null || it.dueDate > now) {
                reminderScheduler.schedule(it.copy(id = nextId))
            }
        }
    }

    suspend fun toggleComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        if (task.isComplete) {
            val reopened = task.copy(isComplete = false, completedAt = null)
            taskDao.update(reopened)
            reminderScheduler.schedule(reopened)
        } else {
            markComplete(taskId, now)
        }
    }

    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int, todayMask: Int): List<Task> =
        taskDao.getActiveTasks(now, currentMinuteOfDay, todayMask)

    suspend fun getAllTasks(): List<Task> = taskDao.getAllOnce()

    suspend fun getFolders(): List<Task> = getAllTasks().filter { it.type == TaskType.FOLDER }

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    suspend fun updateTask(task: Task) {
        taskDao.update(task)
        reminderScheduler.schedule(task)
    }

    suspend fun setSequential(folderId: Long, sequential: Boolean) {
        val task = taskDao.getById(folderId) ?: return
        taskDao.update(task.copy(sequential = sequential))
    }

    suspend fun setDueDate(taskId: Long, dueDate: Long?) {
        val task = taskDao.getById(taskId) ?: return
        val updated = task.copy(dueDate = dueDate)
        taskDao.update(updated)
        reminderScheduler.schedule(updated)
    }

    suspend fun getTasksUnderFolder(folderId: Long): List<Task> = taskDao.getLeafTasksUnder(folderId)

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
}
