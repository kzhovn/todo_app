package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.repository.InheritedField
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

    fun now(): Long = clock()

    fun rolloverHour(): Int = store.getValue(Store.ROLLOVER_HOUR_KEY)?.toIntOrNull() ?: DEFAULT_ROLLOVER_HOUR

    fun get(id: Long): Task? = store.get(TASKS, id)?.takeUnless { it.isDeleted }?.toTask()?.takeUnless { it.isExpired(clock()) }

    // Deletes "just for today" tasks whose day is over, so the phone drops them too even if it was
    // offline at rollover. Reads already hide them; this makes it permanent.
    fun purgeExpired() = store.transaction {
        val now = clock()
        store.all(TASKS).filter { !it.isDeleted && it.toTask().isExpired(now) }.forEach { tombstone(it.id, now) }
    }

    fun deletedTask(id: Long): Task? = store.get(TASKS, id)?.takeIf { it.isDeleted }?.toTask()

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
        val created = task.copy(id = newId()).withRules(clock())
        store.write(TASKS, created.id, taskFields(created, emptySet(), emptySet()), clock())
        return created
    }

    fun update(id: Long, change: (Task) -> Task) {
        val row = store.get(TASKS, id)?.takeUnless { it.isDeleted } ?: return
        writeTask(change(row.toTask()).withRules(clock()), row)
    }

    fun setStarred(id: Long, starred: Boolean) = update(id) { it.copy(isStarred = starred) }

    fun toggleStar(id: Long) = update(id) { it.copy(isStarred = !it.isStarred) }

    // Hidden from Active until then, like the app's snooze.
    fun snooze(id: Long, until: Long) = update(id) { it.copy(startDate = until) }

    fun contexts(): List<TaskContext> = store.all(CONTEXTS).filterNot { it.isDeleted }.map { it.toContext() }

    fun contextIdsByTask(): Map<Long, Set<Long>> = liveRows().associate { it.id to it.contextIds() }

    fun dependsOn(id: Long): Set<Long> = store.get(TASKS, id)?.dependsOn().orEmpty()

    fun dependencyEdges(): List<TaskDependency> = liveRows().flatMap { row -> row.dependsOn().map { TaskDependency(row.id, it) } }

    // The editor's save: the whole task plus its context and dependency sets. A parent or dependency
    // that would make a loop, or points at nothing, is dropped rather than trusted from the form.
    fun edit(task: Task, contextIds: Set<Long>, dependsOn: Set<Long>) = store.transaction {
        val byId = tasks().associateBy { it.id }
        val current = byId[task.id] ?: return@transaction
        val parentId = task.parentId.let { p -> if (p == null || (p in byId && !wouldCreateCycle(p, task.id, byId))) p else current.parentId }
        val folder = task.type == TaskType.FOLDER
        val saved = task.copy(
            parentId = parentId,
            // Lands at the end of a new sibling list, like reparent.
            position = if (parentId != current.parentId) null else task.position,
            dueDate = task.dueDate.takeUnless { folder },
            recurrenceType = task.recurrenceType.takeUnless { folder },
            recurrenceRule = task.recurrenceRule.takeUnless { folder },
            reminderOffsetMinutes = task.reminderOffsetMinutes.takeUnless { folder }
        ).withRules(clock())
        // A folder can't be completed, so it can't wait on anything.
        val edges = dependencyEdges().filter { it.taskId != task.id }
        val deps = if (folder) emptySet() else dependsOn.filterTo(mutableSetOf()) {
            it != task.id && byId[it]?.type.let { t -> t == TaskType.TASK || t == TaskType.PROJECT } && !wouldCreateDependencyCycle(it, task.id, edges)
        }
        val contexts = contexts().map { it.id }.toSet().let { known -> contextIds.filterTo(mutableSetOf()) { it in known } }
        store.write(TASKS, task.id, JsonObject(taskFields(saved, contexts, deps) - DELETED_AT), clock())
    }

    // Moves a task under newParentId, at the end of its children, unless that would make a loop.
    fun reparent(id: Long, newParentId: Long) {
        val byId = tasks().associateBy { it.id }
        if (newParentId !in byId || wouldCreateCycle(newParentId, id, byId)) return
        update(id) { it.copy(parentId = newParentId, position = null) }
    }

    // Subtasks that set their own value for one of these inherited fields, and so wouldn't follow
    // a change to it on this task. Mirrors TaskRepository.descendantsOverriding.
    fun descendantsOverriding(id: Long, fields: Set<InheritedField>): List<Task> {
        val rows = liveRows().associateBy { it.id }
        return (subtreeIds(id) - id).mapNotNull { rows[it] }.filter { row ->
            val d = row.toTask()
            fields.any { field ->
                when (field) {
                    InheritedField.START -> d.startDate != null
                    InheritedField.DUE -> d.dueDate != null
                    InheritedField.ICON -> d.icon != null
                    InheritedField.CONTEXTS -> row.contextIds().isNotEmpty()
                }
            }
        }.map { it.toTask() }
    }

    // Clears those fields on the given tasks, so they inherit from their ancestors again.
    fun clearInherited(tasks: List<Task>, fields: Set<InheritedField>) = store.transaction {
        for (t in tasks) {
            val row = store.get(TASKS, t.id) ?: continue
            val cleared = row.toTask().let {
                it.copy(
                    startDate = it.startDate.takeUnless { InheritedField.START in fields },
                    dueDate = it.dueDate.takeUnless { InheritedField.DUE in fields },
                    icon = it.icon.takeUnless { InheritedField.ICON in fields }
                )
            }
            val contexts = if (InheritedField.CONTEXTS in fields) emptySet() else row.contextIds()
            store.write(TASKS, t.id, JsonObject(taskFields(cleared, contexts, row.dependsOn()) - DELETED_AT), clock())
        }
    }

    // Makes taskId wait for dependsOnId, unless that would create a dependency loop.
    fun addDependency(taskId: Long, dependsOnId: Long) = store.transaction {
        val rows = liveRows()
        val row = rows.firstOrNull { it.id == taskId } ?: return@transaction
        val edges = rows.flatMap { r -> r.dependsOn().map { TaskDependency(r.id, it) } }
        if (dependsOnId == taskId || wouldCreateDependencyCycle(dependsOnId, taskId, edges)) return@transaction
        store.write(TASKS, taskId, JsonObject(taskFields(row.toTask(), row.contextIds(), row.dependsOn() + dependsOnId) - DELETED_AT), clock())
    }

    // Mirrors TaskRepository.markComplete: the next instance keeps the task's contexts (not its
    // dependencies) and gets fresh copies of its subtasks.
    fun complete(id: Long) = store.transaction {
        val now = clock()
        val task = get(id)?.takeUnless { it.isComplete } ?: return@transaction
        val completed = task.copy(isComplete = true, completedAt = now)
        update(id) { completed }
        RecurrenceEngine.nextInstance(completed, now)?.let { next ->
            val rows = liveRows().associateBy { it.id }
            val descendants = (subtreeIds(id) - id).mapNotNull { rows[it]?.toTask() }
            val copies = listOf(id to next) + RecurrenceEngine.successorSubtasks(completed, next, descendants)
            for ((originalId, copy) in copies) {
                store.write(TASKS, copy.id, taskFields(copy, rows[originalId]?.contextIds().orEmpty(), emptySet()), now)
            }
        }
    }

    fun uncomplete(id: Long) = store.transaction {
        val task = get(id)?.takeIf { it.isComplete } ?: return@transaction
        RecurrenceEngine.untouchedSuccessor(task, tasks())?.let { successor ->
            val now = clock()
            subtreeIds(successor.id).forEach { tombstone(it, now) }
        }
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

    private fun liveRows(): List<SyncRow> = clock().let { now -> store.all(TASKS).filterNot { it.isDeleted || it.toTask().isExpired(now) } }

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
