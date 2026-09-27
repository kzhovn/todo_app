package com.kzhovn.todoapp.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.recurrence.toTaskFields
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.changedInheritedFields
import kotlinx.coroutines.launch

// The task editor's state and everything it does to the data; TaskEditActivity is just the screen.
class TaskEditViewModel(
    private val repository: TaskRepository,
    private val contextRepository: ContextRepository,
    val taskId: Long = 0L,
    initialType: TaskType = TaskType.TASK
) : ViewModel() {
    val isNew get() = taskId == 0L

    var task by mutableStateOf(Task(id = taskId, title = "", type = initialType))
    // For an existing task, Save stays disabled until it has loaded: a tap before that would commit
    // the empty placeholder, wiping the task's title and every other field.
    var isLoaded by mutableStateOf(isNew)
        private set
    var recurrence by mutableStateOf(RecurrenceSelection(RecurrencePreset.NONE))
    var contextIds by mutableStateOf<Set<Long>>(emptySet())
    var dependencyIds by mutableStateOf<Set<Long>>(emptySet())

    // A new task has no id yet: its related tasks wait here and are linked on save.
    var pendingSubtasks by mutableStateOf<List<String>>(emptyList())
    var pendingChildIds by mutableStateOf<Set<Long>>(emptySet())
    var pendingDependentIds by mutableStateOf<Set<Long>>(emptySet())
    var pendingDependents by mutableStateOf<List<String>>(emptyList())

    var allTasks by mutableStateOf<List<Task>>(emptyList())
        private set
    var allContexts by mutableStateOf<List<TaskContext>>(emptyList())
        private set
    var dependencyEdges by mutableStateOf<List<TaskDependency>>(emptyList())
        private set
    val allById by derivedStateOf { allTasks.associateBy { it.id } }
    val folders by derivedStateOf { allTasks.filter { it.type == TaskType.FOLDER } }

    // As loaded (or last auto-saved), to tell what an edit changed. The picker only knows simple
    // repeat rules; an imported one shows approximately, so it's rewritten only if actually changed.
    private var original: Task? = null
    private var originalContextIds: Set<Long> = emptySet()
    private var originalDependencyIds: Set<Long> = emptySet()
    private var loadedRecurrence: RecurrenceSelection? = null

    suspend fun load(draft: Task?, draftDependsOn: Long?) {
        if (isNew) {
            draft?.let { task = it }
            draftDependsOn?.let { dependencyIds = setOf(it) }
        } else {
            repository.getTask(taskId)?.let { loaded ->
                task = loaded
                recurrence = recurrenceSelectionFromTask(loaded.recurrenceType, loaded.recurrenceRule)
                loadedRecurrence = recurrence
            }
            dependencyIds = repository.getDependencyIds(taskId)
            contextIds = contextRepository.getContextsForTask(taskId).map { it.id }.toSet()
            markSaved(task)
            isLoaded = true
        }
        refresh()
        refreshContexts()
    }

    // After returning from a screen opened from here (a new subtask, the contexts screen).
    suspend fun refresh() {
        allTasks = repository.getAllTasks()
        dependencyEdges = repository.getAllDependencyEdges()
    }

    suspend fun refreshContexts() {
        allContexts = contextRepository.getAllContexts()
    }

    private fun markSaved(saved: Task) {
        original = saved
        originalContextIds = contextIds
        originalDependencyIds = dependencyIds
        loadedRecurrence = recurrence
    }

    // Auto-save only writes what changed: rewriting unchanged fields would bump their sync clocks and
    // could undo an edit made meanwhile on another device.
    fun isDirty(): Boolean =
        if (isNew) task.title.isNotBlank()
        else original.let { o -> o != null && (task != o || contextIds != originalContextIds || dependencyIds != originalDependencyIds || recurrence != loadedRecurrence) }

    sealed interface Question {
        // A project needs a first step.
        data object FirstStep : Question
        // Subtasks with their own value for a changed inherited field: update them to follow?
        data class UpdateSubtasks(val overriding: List<Task>, val fields: Set<InheritedField>) : Question
    }

    // What saving needs asked first, if anything.
    suspend fun questionBeforeSave(): Question? {
        val hasSubtasks = pendingSubtasks.isNotEmpty() || pendingChildIds.isNotEmpty() || (!isNew && allTasks.any { it.parentId == taskId })
        if (task.type == TaskType.PROJECT && !hasSubtasks) return Question.FirstStep
        val before = original ?: return null
        val changed = changedInheritedFields(before, task, originalContextIds, contextIds)
        // Only subtasks that override a changed field need asking; the rest already follow it.
        val overriding = if (changed.isEmpty()) emptyList() else repository.descendantsOverriding(taskId, changed)
        if (overriding.isEmpty()) return null
        return Question.UpdateSubtasks(overriding, changed.filterTo(mutableSetOf()) { f -> overriding.any { overrides(it, f) } })
    }

    // The task as it will be stored. Folders don't carry task-only fields or relations: a due date
    // would keep scheduling a reminder, and a folder can't be completed, so leaving it as someone's
    // dependency would block that task forever. They keep contexts (children inherit them).
    private fun taskToSave(): Pair<Task, Set<Long>> =
        if (task.type == TaskType.FOLDER) {
            task.copy(dueDate = null, recurrenceType = null, recurrenceRule = null, reminderOffsetMinutes = null, durationMinutes = null) to emptySet()
        } else {
            val (recurrenceType, recurrenceRule) = if (recurrence == loadedRecurrence) task.recurrenceType to task.recurrenceRule else recurrence.toTaskFields()
            task.copy(recurrenceType = recurrenceType, recurrenceRule = recurrenceRule) to dependencyIds
        }

    // Saves the task with its contexts, dependencies and pending relations. clearOn/fields: subtasks to
    // make follow this task again (see Question.UpdateSubtasks). Afterwards the saved state is the
    // baseline for isDirty, since an auto-save keeps the editor open.
    fun saveEdits(clearOn: List<Task> = emptyList(), fields: Set<InheritedField> = emptySet(), firstStep: String? = null, onSaved: (savedId: Long, saved: Task) -> Unit) {
        val (toSave, dependencies) = taskToSave()
        val contexts = contextIds
        save(toSave) { savedId ->
            repository.setDependencies(savedId, dependencies)
            contextRepository.setTaskContexts(savedId, contexts)
            repository.clearInherited(clearOn, fields)
            firstStep?.let { repository.createTask(Task(title = it, parentId = savedId)) }
            pendingSubtasks.forEach { repository.createTask(QuickAddParser.parse(it).copy(parentId = savedId)) }
            pendingChildIds.forEach { repository.reparent(it, savedId) }
            pendingDependentIds.forEach { repository.addDependency(it, savedId) }
            pendingDependents.forEach { repository.addDependency(repository.createTask(QuickAddParser.parse(it).copy(parentId = folderId(toSave))), savedId) }
            task = toSave
            markSaved(toSave)
            onSaved(savedId, toSave)
        }
    }

    fun save(task: Task, onSaved: suspend (Long) -> Unit) {
        viewModelScope.launch {
            val savedId = if (task.id == 0L) repository.createTask(task) else {
                repository.updateTask(task)
                task.id
            }
            onSaved(savedId)
        }
    }

    // New related tasks go in this task's folder, as related work usually belongs together.
    fun folderId(of: Task = task): Long? = of.parentId?.takeIf { allById[it]?.type == TaskType.FOLDER }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); refresh() }

    fun toggleComplete(id: Long) = act { repository.toggleComplete(id, System.currentTimeMillis()) }

    fun deleteItem(item: Task) = act { repository.deleteTask(item) }

    // "milk, eggs" adds two.
    fun addItems(text: String) {
        if (isNew) pendingSubtasks = pendingSubtasks + splitItems(text) else act { repository.addItems(taskId, text) }
    }

    fun uncheckAll() = act { repository.uncheckAll(taskId) }

    fun clearChecked() = act { repository.clearChecked(taskId) }

    fun completeChecklist(moveUncheckedToNewList: Boolean, onDone: () -> Unit) =
        viewModelScope.launch { repository.completeChecklist(taskId, moveUncheckedToNewList, System.currentTimeMillis()); onDone() }

    // An existing task moved under this one.
    fun adoptSubtask(child: Task) {
        if (isNew) pendingChildIds = pendingChildIds + child.id else act { repository.reparent(child.id, taskId) }
    }

    fun addDependent(dependent: Task, onAdded: () -> Unit) {
        if (isNew) pendingDependentIds = pendingDependentIds + dependent.id
        else act { repository.addDependency(dependent.id, taskId); onAdded() }
    }

    fun removeDependent(dependentId: Long) = act { repository.removeDependency(dependentId, taskId) }

    // The prerequisite is created right away and ticked; the dependency itself saves with the task.
    fun createPrerequisite(text: String) {
        val parsed = QuickAddParser.parse(text)
        if (parsed.title.isNotBlank()) act { dependencyIds = dependencyIds + repository.createTask(parsed.copy(parentId = folderId())) }
    }

    fun createFolder(name: String) = act { task = task.copy(parentId = repository.createTask(Task(type = TaskType.FOLDER, title = name))) }

    suspend fun descendantCount(): Int = if (isNew) 0 else repository.countDescendants(taskId)

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.deleteTask(task); onDone() }
}

// Whether a subtask sets its own value for an inherited field (dates/icon here; contexts are checked
// by the repository, which knows the assignments).
private fun overrides(task: Task, field: InheritedField): Boolean = when (field) {
    InheritedField.START -> task.startDate != null
    InheritedField.DUE -> task.dueDate != null
    InheritedField.ICON -> task.icon != null
    InheritedField.CONTEXTS -> true
}
