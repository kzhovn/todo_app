package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.notifications.ReminderScheduler
import com.kzhovn.todoapp.recurrence.RecurrenceEngine

class TaskRepository(
    private val taskDao: TaskDao,
    private val reminderScheduler: ReminderScheduler,
    private val taskContextDao: TaskContextDao
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

    // Computed in Kotlin rather than SQL: effective start date and contexts are inherited from
    // ancestors (see resolveEffective), which a single query can't express without several
    // recursive CTEs. At this app's scale, fetching the whole graph once is cheap.
    suspend fun getActiveTasks(now: Long, currentMinuteOfDay: Int, todayMask: Int): List<Task> {
        val all = taskDao.getAllOnce()
        val allById = all.associateBy { it.id }
        val contextsByTaskId = taskContextDao.getAllCrossRefs()
            .groupBy({ it.taskId }, { it.contextId })
            .mapValues { it.value.toSet() }
        val allContexts = taskContextDao.getAll().associateBy { it.id }
        val timeWindows = taskContextDao.getAllTimeWindows().groupBy { it.contextId }

        val blockedByDependency = taskDao.getAllDependencies()
            .filter { edge -> allById[edge.dependsOnTaskId]?.isComplete == false }
            .map { it.taskId }
            .toSet()

        val childrenByParentId = all.groupBy { it.parentId }

        // Under a sequential parent, only the lowest-id incomplete child is workable.
        fun isSequentiallyBlocked(task: Task): Boolean {
            val parent = task.parentId?.let { allById[it] } ?: return false
            if (!parent.sequential) return false
            val firstIncomplete = childrenByParentId[parent.id].orEmpty()
                .filter { !it.isComplete }
                .minByOrNull { it.id } ?: return false
            return firstIncomplete.id != task.id
        }

        fun isContextSatisfied(contextId: Long): Boolean {
            val ctx = allContexts[contextId] ?: return true
            return when (ctx.type) {
                ContextType.PLACE -> ctx.isCurrentlySatisfied
                ContextType.TIME -> timeWindows[contextId].orEmpty().any { w ->
                    (w.daysMask and todayMask) != 0 &&
                        if (w.windowStartMinute <= w.windowEndMinute) {
                            currentMinuteOfDay in w.windowStartMinute..w.windowEndMinute
                        } else { // window spans midnight
                            currentMinuteOfDay >= w.windowStartMinute || currentMinuteOfDay <= w.windowEndMinute
                        }
                }
            }
        }

        return all.filter { task ->
            if (task.type == TaskType.FOLDER || task.isComplete) return@filter false
            if (task.id in blockedByDependency || isSequentiallyBlocked(task)) return@filter false
            val effective = resolveEffective(task, allById, contextsByTaskId)
            (effective.effectiveStartDate == null || effective.effectiveStartDate <= now) &&
                effective.effectiveContextIds.all(::isContextSatisfied)
        }
    }

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

    suspend fun countActiveDescendants(taskId: Long): Int = taskDao.countActiveDescendants(taskId)

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
