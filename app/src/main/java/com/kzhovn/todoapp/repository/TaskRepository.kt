package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

class TaskRepository(private val taskDao: TaskDao) {

    suspend fun createTask(task: Task): Long = taskDao.insert(task)

    suspend fun deleteTask(task: Task) = taskDao.delete(task)

    suspend fun undoDelete(task: Task) = taskDao.insert(task)

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
        taskDao.update(task.copy(startDate = now + durationMillis))
    }

    suspend fun markComplete(taskId: Long, now: Long) {
        val task = taskDao.getById(taskId) ?: return
        val completedTask = task.copy(isComplete = true, completedAt = now)
        taskDao.update(completedTask)
        RecurrenceEngine.nextInstance(completedTask, now)?.let { taskDao.insert(it) }
    }

    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int): List<Task> =
        taskDao.getActiveTasks(now, currentMinuteOfDay)

    suspend fun getAllTasks(): List<Task> = taskDao.getAllOnce()

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    suspend fun updateTask(task: Task) = taskDao.update(task)

    suspend fun setSequential(folderId: Long, sequential: Boolean) {
        val task = taskDao.getById(folderId) ?: return
        taskDao.update(task.copy(sequential = sequential))
    }

    suspend fun setDueDate(taskId: Long, dueDate: Long?) {
        val task = taskDao.getById(taskId) ?: return
        taskDao.update(task.copy(dueDate = dueDate))
    }

    suspend fun getTasksUnderFolder(folderId: Long): List<Task> = taskDao.getLeafTasksUnder(folderId)

    suspend fun addDependency(taskId: Long, dependsOnTaskId: Long) =
        taskDao.insertDependency(TaskDependency(taskId, dependsOnTaskId))
}
