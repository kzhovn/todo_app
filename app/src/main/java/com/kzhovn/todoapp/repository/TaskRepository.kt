package com.kzhovn.todoapp.repository

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

    // Deleting a folder/task with children would orphan them: parentId still points at
    // the now-deleted row, and there's no cascade/reparent, so they'd silently vanish
    // from every screen. Returns false (and deletes nothing) rather than doing that.
    suspend fun deleteTask(task: Task): Boolean {
        if (taskDao.countChildren(task.id) > 0) return false
        taskDao.delete(task)
        reminderScheduler.cancel(task)
        return true
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

    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int): List<Task> =
        taskDao.getActiveTasks(now, currentMinuteOfDay)

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

    suspend fun search(query: String): List<Task> = taskDao.search(query)
}
