package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.newId

class ContextRepository(
    private val contextDao: TaskContextDao,
    // See TaskRepository's.
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun createContext(context: TaskContext): Long =
        contextDao.insert(if (context.id == 0L) context.copy(id = newId()) else context)

    suspend fun getAllContexts(): List<TaskContext> = contextDao.getAll()

    suspend fun getContextsForTask(taskId: Long): List<TaskContext> = contextDao.getContextsForTask(taskId)

    suspend fun setTaskContexts(taskId: Long, contextIds: Set<Long>) = transaction {
        val current = contextDao.getContextsForTask(taskId).map { it.id }.toSet()
        (contextIds - current).forEach { contextDao.assignContext(TaskContextCrossRef(taskId, it)) }
        (current - contextIds).forEach { contextDao.unassignContext(taskId, it) }
    }

    suspend fun getTimeWindows(contextId: Long): List<ContextTimeWindow> = contextDao.getTimeWindows(contextId)

    suspend fun addTimeWindow(window: ContextTimeWindow): Long = contextDao.insertTimeWindow(window)

    suspend fun updateContext(context: TaskContext) = contextDao.update(context)

    suspend fun setForced(contextId: Long, forced: Boolean?) = contextDao.setForced(contextId, forced)

    // The contexts screen's save: the context with its time windows, replaced wholesale (a context switched
    // to a place just drops its old ones), keeping whatever it's set to by hand. Returns its id.
    suspend fun saveContext(context: TaskContext, windows: List<ContextTimeWindow>): Long {
        var id = context.id
        transaction {
            if (id == 0L) id = createContext(context) else updateContext(context.copy(forced = contextDao.getById(id)?.forced))
            contextDao.deleteTimeWindowsForContext(id)
            if (context.type == ContextType.TIME) windows.forEach { contextDao.insertTimeWindow(it.copy(id = 0, contextId = id)) }
        }
        return id
    }

    suspend fun deleteContext(contextId: Long) = transaction {
        contextDao.deleteTimeWindowsForContext(contextId)
        contextDao.deleteContextAssignments(contextId)
        contextDao.deleteContext(contextId)
    }
}
