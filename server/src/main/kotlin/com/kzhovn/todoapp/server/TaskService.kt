package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.completed
import com.kzhovn.todoapp.data.CurrentTask
import com.kzhovn.todoapp.data.DEFAULT_FOLDER
import com.kzhovn.todoapp.quickadd.QuickAdd
import com.kzhovn.todoapp.quickadd.planQuickAdd
import com.kzhovn.todoapp.data.pinnedTask
import com.kzhovn.todoapp.data.isDoable
import com.kzhovn.todoapp.data.searchTasks
import com.kzhovn.todoapp.data.findFolder
import com.kzhovn.todoapp.repository.blockerFor
import com.kzhovn.todoapp.repository.applyTo
import com.kzhovn.todoapp.repository.overridesInherited
import com.kzhovn.todoapp.data.planMoveNextTo
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.data.isChecklistItem
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.data.newTaskPositions
import com.kzhovn.todoapp.data.ContextTimeWindow
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.DEFAULT_ROLLOVER_HOUR
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskOrder
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.newId
import com.kzhovn.todoapp.data.wouldCreateCycle
import com.kzhovn.todoapp.data.wouldCreateDependencyCycle
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.recurrence.RecurrenceEngine
import com.kzhovn.todoapp.data.folderColorAssignments
import com.kzhovn.todoapp.data.stalledProjects
import com.kzhovn.todoapp.repository.BulkEdit
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.repository.computeActiveTasks
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.sync.CONTEXTS
import com.kzhovn.todoapp.sync.DELETED_AT
import com.kzhovn.todoapp.sync.SyncRow
import com.kzhovn.todoapp.sync.TASKS
import com.kzhovn.todoapp.sync.contextFields
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

    // Deletes "Today only" tasks whose day is over, so the phone drops them too even if it was
    // offline at rollover. Reads already hide them; this makes it permanent.
    fun purgeExpired() = store.transaction {
        val now = clock()
        store.all(TASKS).filter { !it.isDeleted && it.toTask().isExpired(now) }.forEach { tombstone(it.id, now) }
    }

    fun deletedTask(id: Long): Task? = store.get(TASKS, id)?.takeIf { it.isDeleted }?.toTask()

    fun tasks(): List<Task> = clock().let { now -> decoded().let { d -> d.rows.mapNotNull { d.tasks[it.id]?.takeUnless { t -> t.isExpired(now) } } } }

    fun active(folderId: Long? = null): List<Task> {
        val rows = liveRows()
        val all = tasks()
        val contexts = contextsByTaskId(rows)
        val contextRows = store.all(CONTEXTS).filterNot { it.isDeleted }
        val active = computeActiveTasks(
            all = all,
            contextsByTaskId = contexts,
            contexts = contextRows.map { it.toContext() },
            timeWindows = contextRows.flatMap { it.timeWindows() },
            dependencies = rows.flatMap { row -> row.dependsOn().map { TaskDependency(row.id, it) } },
            now = clock()
        )
        return urgentFirst(if (folderId == null) active else active.filter { it.id in subtreeIds(folderId) }, clock(), all.associateBy { it.id }, contexts)
    }

    fun doing(folderId: Long? = null): List<Task> = filterDoing(active(folderId), clock(), tasks().associateBy { it.id }, contextIdsByTask())

    fun effectiveDueDate(task: Task): Long? = resolveEffective(task, tasks().associateBy { it.id }, contextIdsByTask()).effectiveDueDate

    // Open tasks anywhere under the folder, for `.list <folder>`.
    fun openInFolder(folderId: Long): List<Task> {
        val ids = subtreeIds(folderId)
        val all = tasks()
        val byId = all.associateBy { it.id }
        return all.filter { it.id in ids && it.id != folderId && it.type.isDoable && !it.isComplete && !isChecklistItem(it, byId) }
    }

    fun folders(): List<Task> = tasks().filter { it.type == TaskType.FOLDER }

    // Stores colour slots for folders without one (see folderColorAssignments), like the app does.
    fun ensureFolderColors() = folderColorAssignments(tasks()).forEach { t -> update(t.id) { it.copy(colorIndex = t.colorIndex) } }

    fun findFolder(name: String): Task? = findFolder(tasks(), name)

    // Placed like the app's createTask (see newTaskPositions).
    fun create(task: Task, contextIds: Set<Long> = emptySet()): Task = store.transaction {
        val all = tasks()
        val new = task.copy(id = newId()).withRules(clock())
        val positions = newTaskPositions(all, new)
        val created = new.copy(position = positions[new.id] ?: new.position)
        store.write(TASKS, created.id, taskFields(created, contextIds, emptySet()), clock())
        positions.forEach { (id, position) -> if (id != created.id) update(id) { it.copy(position = position) } }
        created
    }

    fun update(id: Long, change: (Task) -> Task) {
        val row = store.get(TASKS, id)?.takeUnless { it.isDeleted } ?: return
        writeTask(change(row.toTask()).withRules(clock()), row)
    }

    // The current task: the pin, with its timer and focus session (see CurrentTask), shared by every device.
    private fun current(change: (List<Task>) -> List<Task>) = store.transaction {
        change(tasks()).forEach { t -> update(t.id) { t } }
    }

    fun pin(id: Long) = current { CurrentTask.pin(it, id, clock()) }
    fun unpin() = current { CurrentTask.unpin(it) }
    fun startTimer(id: Long, minutes: Int) = current { CurrentTask.startTimer(it, id, minutes, clock()) }
    fun pauseTimer() = current { CurrentTask.pauseTimer(it, clock()) }
    fun resumeTimer() = current { CurrentTask.resumeTimer(it, clock()) }
    fun addTime(minutes: Int) = current { CurrentTask.addTime(it, minutes, clock()) }
    fun focus(id: Long) = current { CurrentTask.focus(it, id, clock()) }

    fun pinned(): Task? = pinnedTask(tasks())

    fun focusSession(): Task? = CurrentTask.focusSession(tasks())

    // Quick add from the web or the desktop tray: parsed like the app's, into Personal (or the folder
    // being looked at) unless it names one. Added while looking at Doing, it starts starred so it shows
    // up right there, like the Doing widget's.
    fun quickAdd(text: String, fromDoing: Boolean, folderId: Long? = null): Task? {
        val folder = folderId?.takeIf { get(it)?.type == TaskType.FOLDER } ?: findFolder(DEFAULT_FOLDER)?.id
        return add(planQuickAdd(text), folder, star = fromDoing)
    }

    fun planQuickAdd(text: String): QuickAdd = planQuickAdd(text, tasks(), contexts(), clock(), rolloverHour())

    // Carries out a quick add (see planQuickAdd): the task with its contexts, items, pin or focus, or
    // items into an existing checklist. Returns the task (the checklist, for items).
    fun add(add: QuickAdd, defaultParent: Long?, star: Boolean = false): Task? = store.transaction {
        if (add.isEmpty) return@transaction null
        add.intoChecklist?.let { id -> addItems(id, add.items); return@transaction get(id) }
        val task = add.task ?: return@transaction null
        val created = create(task.copy(parentId = task.parentId ?: defaultParent, isStarred = task.isStarred || (star && !task.isMaybe)), add.contextIds)
        add.items.forEach { create(Task(title = it, parentId = created.id)) }
        if (add.focus) focus(created.id) else if (add.pin) pin(created.id)
        created
    }

    fun setStarred(id: Long, starred: Boolean) = update(id) { it.copy(isStarred = starred) }

    fun toggleStar(id: Long) = update(id) { it.copy(isStarred = !it.isStarred) }

    // Hidden from Active until then, like the app's snooze.
    fun snooze(id: Long, until: Long) = update(id) { it.copy(startDate = until) }

    fun contexts(): List<TaskContext> = store.all(CONTEXTS).filterNot { it.isDeleted }.map { it.toContext() }

    fun contextIdsByTask(): Map<Long, Set<Long>> = liveRows().associate { it.id to it.contextIds() }

    fun timeWindows(contextId: Long): List<ContextTimeWindow> =
        store.get(CONTEXTS, contextId)?.takeUnless { it.isDeleted }?.timeWindows().orEmpty()

    // The web's side of the phone's contexts screen. Whether a place context currently holds is the
    // phone's to say (it sees the wifi), so a save keeps whatever the phone last reported.
    fun saveContext(context: TaskContext, windows: List<ContextTimeWindow>): TaskContext {
        val existing = store.get(CONTEXTS, context.id)?.takeUnless { it.isDeleted }?.toContext()
        val place = context.type == ContextType.PLACE
        val saved = context.copy(
            id = existing?.id ?: newId(),
            wifiSsid = context.wifiSsid.takeIf { place },
            isCurrentlySatisfied = existing?.isCurrentlySatisfied ?: false
        )
        store.write(CONTEXTS, saved.id, contextFields(saved, if (place) emptyList() else windows), clock())
        return saved
    }

    // Like the phone's: the context goes, and so does every task's assignment to it.
    fun deleteContext(id: Long) = store.transaction {
        val now = clock()
        liveRows().filter { id in it.contextIds() }.forEach { row ->
            store.write(TASKS, row.id, JsonObject(taskFields(row.toTask(), row.contextIds() - id, row.dependsOn()) - DELETED_AT), now)
        }
        store.write(CONTEXTS, id, JsonObject(mapOf(DELETED_AT to JsonPrimitive(now))), now)
    }

    fun search(query: String, filters: SearchFilters): List<Task> = searchTasks(tasks(), contextIdsByTask(), query, filters)

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
            reminderOffsetMinutes = task.reminderOffsetMinutes.takeUnless { folder },
            durationMinutes = task.durationMinutes.takeUnless { folder }
        ).withRules(clock())
        // A folder can't be completed, so it can't wait on anything.
        val edges = dependencyEdges().filter { it.taskId != task.id }
        val deps = if (folder) emptySet() else dependsOn.filterTo(mutableSetOf()) {
            it != task.id && byId[it]?.type.let { t -> t != null && (t.isDoable || t == TaskType.PROJECT) } && !wouldCreateDependencyCycle(it, task.id, edges)
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

    // See planMoveNextTo (shared with the app's TaskRepository.moveNextTo).
    fun moveNextTo(id: Long, anchorId: Long, after: Boolean) = store.transaction {
        val move = planMoveNextTo(tasks(), id, anchorId, after) ?: return@transaction
        move.positions.forEach { (taskId, position) -> update(taskId) { it.copy(parentId = move.parentId, position = position) } }
    }

    // The outliner's moves, among the open siblings the tree shows (completed ones are hidden there).
    private fun openSiblings(task: Task) = tasks().filter { it.parentId == task.parentId && !it.isComplete }.sortedWith(TaskOrder)

    // Tab: becomes the last child of the sibling above it.
    fun indent(id: Long) {
        val task = get(id) ?: return
        val siblings = openSiblings(task)
        siblings.getOrNull(siblings.indexOf(task) - 1)?.let { reparent(id, it.id) }
    }

    // Shift-Tab: becomes the sibling right after its parent.
    fun outdent(id: Long) {
        val parentId = get(id)?.parentId ?: return
        moveNextTo(id, parentId, after = true)
    }

    // Alt-Up/Down: swaps places with the sibling above/below.
    fun moveAmongSiblings(id: Long, down: Boolean) {
        val task = get(id) ?: return
        val siblings = openSiblings(task)
        siblings.getOrNull(siblings.indexOf(task) + if (down) 1 else -1)?.let { moveNextTo(id, it.id, after = down) }
    }

    // Subtasks that set their own value for one of these inherited fields, and so wouldn't follow
    // a change to it on this task (see overridesInherited).
    fun descendantsOverriding(id: Long, fields: Set<InheritedField>): List<Task> {
        val rows = liveRows().associateBy { it.id }
        return (subtreeIds(id) - id).mapNotNull { rows[it] }
            .filter { row -> val d = row.toTask(); fields.any { overridesInherited(d, row.contextIds(), it) } }
            .map { it.toTask() }
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
        val completed = task.completed(now)
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

    fun activeDescendantCount(id: Long): Int =
        (subtreeIds(id) - id).mapNotNull(::get).count { it.type != TaskType.FOLDER && !it.isComplete }

    fun removeDependency(taskId: Long, dependsOnId: Long) = store.transaction {
        val row = liveRows().firstOrNull { it.id == taskId } ?: return@transaction
        store.write(TASKS, taskId, JsonObject(taskFields(row.toTask(), row.contextIds(), row.dependsOn() - dependsOnId) - DELETED_AT), clock())
    }

    // Checklists; mirror TaskRepository's addItems, clearChecked, uncheckAll and completeChecklist.
    fun addItems(checklistId: Long, text: String) = addItems(checklistId, splitItems(text))

    private fun addItems(checklistId: Long, items: List<String>) = store.transaction { items.forEach { create(Task(title = it, parentId = checklistId)) } }

    fun findChecklist(name: String): Task? = tasks().firstOrNull { it.type == TaskType.CHECKLIST && !it.isComplete && it.title.trim().equals(name.trim(), ignoreCase = true) }

    fun clearChecked(checklistId: Long) = store.transaction { tasks().filter { it.parentId == checklistId && it.isComplete }.forEach { delete(it.id) } }

    fun uncheckAll(checklistId: Long) = store.transaction { tasks().filter { it.parentId == checklistId && it.isComplete }.forEach { uncomplete(it.id) } }

    fun completeChecklist(checklistId: Long, moveUncheckedToNewList: Boolean) = store.transaction {
        val row = liveRows().firstOrNull { it.id == checklistId } ?: return@transaction
        val unchecked = tasks().filter { it.parentId == checklistId && !it.isComplete }
        if (moveUncheckedToNewList && unchecked.isNotEmpty()) {
            val copy = row.toTask().copy(id = newId(), position = null, recurrenceType = null, recurrenceRule = null)
            store.write(TASKS, copy.id, taskFields(copy, row.contextIds(), emptySet()), clock())
            unchecked.forEach { item -> update(item.id) { it.copy(parentId = copy.id) } }
        }
        completeWithDescendants(checklistId)
    }

    // Mirrors TaskRepository.completeWithDescendants: each goes through complete(), so a recurring
    // subtask still spawns its next instance.
    fun completeWithDescendants(id: Long) = store.transaction {
        (subtreeIds(id) - id).mapNotNull(::get).filter { it.type != TaskType.FOLDER && !it.isComplete }.forEach { complete(it.id) }
        complete(id)
    }

    // Mirrors TaskRepository.promoteChildrenToTopLevel: only direct children move out, so they
    // survive as independent tasks.
    fun promoteChildren(id: Long) = store.transaction {
        tasks().filter { it.parentId == id }.forEach { child -> update(child.id) { it.copy(parentId = null, position = null) } }
    }

    // Open projects whose steps are all done: time to complete them or add the next step.
    fun stalled(): List<Task> = stalledProjects(tasks())

    // Only tasks and checklists (isDoable; none of the bulk properties apply to folders or projects); see BulkEdit.applyTo, shared with the app.
    fun applyBulkEdit(ids: Collection<Long>, change: BulkEdit) = store.transaction {
        val byId = tasks().associateBy { it.id }
        val edges = dependencyEdges()
        val contexts = contextIdsByTask()
        for (id in ids) {
            val task = byId[id]?.takeIf { it.type.isDoable } ?: continue
            val blocker = change.blockerFor(id, edges)
            edit(change.applyTo(task, byId), contexts[id].orEmpty() + change.addContextIds - change.removeContextIds, dependsOn(id) + listOfNotNull(blocker))
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

    // Every task decoded once per change to the stored rows (Store.all returns the same list until a
    // write), since one page or tray refresh reads them all several times over.
    private class Decoded(val source: List<SyncRow>, val rows: List<SyncRow>, val tasks: Map<Long, Task>)

    @Volatile
    private var decodedCache: Decoded? = null

    private fun decoded(): Decoded {
        val source = store.all(TASKS)
        decodedCache?.takeIf { it.source === source }?.let { return it }
        val rows = source.filterNot { it.isDeleted }
        return Decoded(source, rows, rows.associate { it.id to it.toTask() }).also { decodedCache = it }
    }

    private fun liveRows(): List<SyncRow> = clock().let { now -> decoded().let { d -> d.rows.filterNot { d.tasks.getValue(it.id).isExpired(now) } } }

    private fun contextsByTaskId(rows: List<SyncRow>) = rows.associate { it.id to it.contextIds() }

    private fun subtreeIds(rootId: Long, rows: List<SyncRow> = liveRows()): Set<Long> {
        val tasks = decoded().tasks
        val children = rows.groupBy({ (tasks[it.id] ?: it.toTask()).parentId }, { it.id })
        val result = mutableSetOf<Long>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (result.add(next)) children[next]?.let(stack::addAll)
        }
        return result
    }
}
