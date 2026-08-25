package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskContextDao

class ContextRepository(private val contextDao: TaskContextDao) {
    suspend fun createContext(context: TaskContext): Long = contextDao.insert(context)

    suspend fun getAllContexts(): List<TaskContext> = contextDao.getAll()

    suspend fun getContextsForTask(taskId: Long): List<TaskContext> = contextDao.getContextsForTask(taskId)

    suspend fun setTaskContexts(taskId: Long, contextIds: Set<Long>) {
        val current = contextDao.getContextsForTask(taskId).map { it.id }.toSet()
        (contextIds - current).forEach { contextDao.assignContext(TaskContextCrossRef(taskId, it)) }
        (current - contextIds).forEach { contextDao.unassignContext(taskId, it) }
    }
}
