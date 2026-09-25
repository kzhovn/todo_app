package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContextDao
import com.kzhovn.todoapp.data.TaskDao
import com.kzhovn.todoapp.data.TaskContextCrossRef
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
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
        val toInsert = (if (task.id == 0L) task.copy(id = newId()) else task).withRules(System.currentTimeMillis())
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

    // Lands at the end of its new sibling list (a stale position from the old list would misplace it).
    suspend fun reparent(taskId: Long, newParentId: Long?) {
        val task = taskDao.getById(taskId) ?: return
        taskDao.update(task.copy(parentId = newParentId, position = null))
    }

    // Moves a task right before/after `anchorId`, under the anchor's parent, and renumbers that whole
    // sibling list 1..n. Renumbering (rather than squeezing a value between neighbours) never runs
    // out of room; it rewrites a few rows per move, which is nothing at one user's scale.
    suspend fun moveNextTo(taskId: Long, anchorId: Long, after: Boolean) {
        val all = taskDao.getAllOnce()
        val byId = all.associateBy { it.id }
        val task = byId[taskId] ?: return
        val anchor = byId[anchorId]?.takeIf { it.id != taskId } ?: return
        val parentId = anchor.parentId
        if (parentId != null && wouldCreateCycle(parentId, taskId, byId)) return
        val siblings = all.filter { it.parentId == parentId && it.id != taskId }.sortedWith(TaskOrder).toMutableList()
        siblings.add(siblings.indexOf(anchor) + if (after) 1 else 0, task.copy(parentId = parentId))
        siblings.forEachIndexed { index, sibling ->
            val position = index + 1L
            if (sibling.position != position || sibling.id == taskId) taskDao.update(sibling.copy(position = position))
        }
    }

    suspend fun getDescendants(taskId: Long): List<Task> = taskDao.getDescendants(taskId)

    // Subtasks that set their own value for one of these inherited fields, and so wouldn't follow
    // a change to it on this task.
    suspend fun descendantsOverriding(taskId: Long, fields: Set<InheritedField>): List<Task> =
        taskDao.getDescendants(taskId).filter { d ->
            fields.any { field ->
                when (field) {
                    InheritedField.START -> d.startDate != null
                    InheritedField.DUE -> d.dueDate != null
                    InheritedField.ICON -> d.icon != null
                    InheritedField.CONTEXTS -> taskContextDao.getContextIdsForTask(d.id).isNotEmpty()
                }
            }
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

    suspend fun getAllTaskContexts(): Map<Long, Set<Long>> =
        taskContextDao.getAllCrossRefs().groupBy({ it.taskId }, { it.contextId }).mapValues { it.value.toSet() }

    suspend fun getFolders(): List<Task> = getAllTasks().filter { it.type == TaskType.FOLDER }

    suspend fun getTask(taskId: Long): Task? = taskDao.getById(taskId)

    suspend fun updateTask(task: Task) {
        taskDao.update(task.withRules(System.currentTimeMillis()))
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

    // Folders are skipped: none of the bulk properties apply to them. A move or dependency that
    // would create a cycle is skipped for that task rather than failing the whole edit.
    suspend fun applyBulkEdit(taskIds: Collection<Long>, edit: BulkEdit) {
        val allById = taskDao.getAllOnce().associateBy { it.id }
        val edges = taskDao.getAllDependencies()
        for (id in taskIds) {
            val task = allById[id]?.takeIf { it.type == TaskType.TASK } ?: continue
            val target = edit.moveTo?.folderId
            val parentId = when {
                edit.moveTo == null -> task.parentId
                target != null && wouldCreateCycle(target, id, allById) -> task.parentId
                else -> target
            }
            updateTask(
                task.copy(
                    isStarred = edit.starred ?: task.isStarred,
                    isMaybe = edit.maybe ?: task.isMaybe,
                    startDate = if (edit.startDate != null) edit.startDate.date else task.startDate,
                    dueDate = if (edit.dueDate != null) edit.dueDate.date else task.dueDate,
                    parentId = parentId
                )
            )
            if (edit.addContextIds.isNotEmpty() || edit.removeContextIds.isNotEmpty()) {
                val current = taskContextDao.getContextIdsForTask(id).toSet()
                (edit.addContextIds - current).forEach { taskContextDao.assignContext(TaskContextCrossRef(id, it)) }
                (edit.removeContextIds intersect current).forEach { taskContextDao.unassignContext(id, it) }
            }
            edit.dependsOnId?.takeIf { it != id && !wouldCreateDependencyCycle(it, id, edges) }
                ?.let { taskDao.insertDependency(TaskDependency(id, it)) }
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
    // their own now-top-level parent) so they survive as independent tasks.
    suspend fun promoteChildrenToTopLevel(taskId: Long) {
        taskDao.getChildren(taskId).forEach { child -> taskDao.update(child.copy(parentId = null)) }
    }
}

// One multi-edit applied to many tasks. Null means "leave as is"; the editable set is limited to
// what makes sense in bulk (no title, recurrence, or folder-ness).
data class BulkEdit(
    val starred: Boolean? = null,
    val maybe: Boolean? = null,
    val startDate: DateChange? = null,
    val dueDate: DateChange? = null,
    val moveTo: FolderChange? = null,
    val addContextIds: Set<Long> = emptySet(),
    val removeContextIds: Set<Long> = emptySet(),
    val dependsOnId: Long? = null
)

data class DateChange(val date: Long?)      // null date = clear it
data class FolderChange(val folderId: Long?) // null folder = move to top level

// The fields subtasks inherit from their parents when they don't set their own (see resolveEffective).
enum class InheritedField(val label: String) { START("start date"), DUE("due date"), CONTEXTS("contexts"), ICON("icon") }
