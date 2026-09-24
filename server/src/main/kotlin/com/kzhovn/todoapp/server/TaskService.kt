package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.repository.computeActiveTasks
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.sync.CONTEXTS
import com.kzhovn.todoapp.sync.DELETED_AT
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextIds
import com.kzhovn.todoapp.sync.dependsOn
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.sync.timeWindows
import com.kzhovn.todoapp.sync.toContext
import com.kzhovn.todoapp.sync.toTask
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The server-side mirror of TaskRepository's write paths, over synced rows instead of Room. Pure
// rules (recurrence, active/doing, inheritance) come from :core so both sides agree.
class TaskService(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {

    fun get(id: Long): Task? = store.get(TASKS, id)?.takeUnless { it.isDeleted }?.toTask()

    fun tasks(): List<Task> = liveRows().map { it.toTask() }

    fun active(folderId: Long? = null): List<Task> {
        val rows = liveRows()
        val contextRows = store.all(CONTEXTS).filterNot { it.isDeleted }
        val active = computeActiveTasks(
            all = rows.map { it.toTask() },
            contextsByTaskId = contextsByTaskId(rows),
            contexts = contextRows.map { it.toContext() },
            timeWindows = contextRows.flatMap { it.timeWindows() },
            dependencies = rows.flatMap { row -> row.dependsOn().map { TaskDependency(row.id, it) } },
            now = clock()
        )
        return if (folderId == null) active else active.filter { it.id in subtreeIds(folderId) }
    }

    fun doing(folderId: Long? = null): List<Task> {
        val rows = liveRows()
        val byId = rows.associate { it.id to it.toTask() }
        val contexts = contextsByTaskId(rows)
        return filterDoing(active(folderId), clock()) { resolveEffective(it, byId, contexts).effectiveDueDate }
    }

    fun effectiveDueDate(task: Task): Long? {
        val rows = liveRows()
        return resolveEffective(task, rows.associate { it.id to it.toTask() }, contextsByTaskId(rows)).effectiveDueDate
    }

    // Open tasks anywhere under the folder, for `.list <folder>`.
    fun openInFolder(folderId: Long): List<Task> {
        val ids = subtreeIds(folderId)
        return tasks().filter { it.id in ids && it.id != folderId && it.type == TaskType.TASK && !it.isComplete }
    }

    fun folders(): List<Task> = tasks().filter { it.type == TaskType.FOLDER }

    fun findFolder(name: String): Task? = folders().firstOrNull { it.title.equals(name.trim(), ignoreCase = true) }

    fun create(task: Task): Task {
        val created = task.copy(id = newId())
        store.write(TASKS, created.id, taskFields(created, emptySet(), emptySet()), clock())
        return created
    }

    fun update(id: Long, change: (Task) -> Task) {
        val row = store.get(TASKS, id)?.takeUnless { it.isDeleted } ?: return
        writeTask(change(row.toTask()), row)
    }

    fun setStarred(id: Long, starred: Boolean) = update(id) { it.copy(isStarred = starred) }

    // Mirrors TaskRepository.markComplete: the spawned instance starts with no contexts/dependencies.
    fun complete(id: Long) = store.transaction {
        val now = clock()
        val task = get(id)?.takeUnless { it.isComplete } ?: return@transaction
        val completed = task.copy(isComplete = true, completedAt = now)
        update(id) { completed }
        RecurrenceEngine.nextInstance(completed, now)?.let {
            store.write(TASKS, it.id, taskFields(it, emptySet(), emptySet()), now)
        }
    }

    fun uncomplete(id: Long) = store.transaction {
        val task = get(id)?.takeIf { it.isComplete } ?: return@transaction
        RecurrenceEngine.untouchedSuccessor(task, tasks())?.let { tombstone(it.id, clock()) }
        update(id) { it.copy(isComplete = false, completedAt = null) }
    }

    // Soft-deletes the whole subtree with one shared timestamp, which is how restore() knows which
    // descendants went down together (vs. ones deleted separately earlier).
    fun delete(id: Long) = store.transaction {
        if (get(id) == null) return@transaction
        val now = clock()
        val ids = subtreeIds(id)
        liveRows().filter { it.id in ids }.forEach { tombstone(it.id, now) }
    }

    fun restore(id: Long) = store.transaction {
        val deletedAt = store.get(TASKS, id)?.deletedAt ?: return@transaction
        val all = store.all(TASKS)
        subtreeIds(id, all).forEach { taskId ->
            if (all.first { it.id == taskId }.deletedAt == deletedAt) {
                store.write(TASKS, taskId, JsonObject(mapOf(DELETED_AT to JsonNull)), clock())
            }
        }
    }

    private fun tombstone(id: Long, now: Long) =
        store.write(TASKS, id, JsonObject(mapOf(DELETED_AT to JsonPrimitive(now))), now)

    private fun writeTask(task: Task, row: SyncRow) =
        store.write(TASKS, task.id, JsonObject(taskFields(task, row.contextIds(), row.dependsOn()) - DELETED_AT), clock())

    private fun liveRows(): List<SyncRow> = store.all(TASKS).filterNot { it.isDeleted }

    private fun contextsByTaskId(rows: List<SyncRow>) = rows.associate { it.id to it.contextIds() }

    private fun subtreeIds(rootId: Long, rows: List<SyncRow> = liveRows()): Set<Long> {
        val children = rows.groupBy({ it.toTask().parentId }, { it.id })
        val result = mutableSetOf<Long>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (result.add(next)) children[next]?.let(stack::addAll)
        }
        return result
    }
}
