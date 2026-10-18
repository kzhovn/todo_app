package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.repository.TemplateChange

import com.kzhovn.todoapp.ui.theme.folderColors
import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.folderOf
import com.kzhovn.todoapp.recurrence.recurrenceToSave
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.CalendarEvent
import com.kzhovn.todoapp.data.mergeEdit
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TaskDependency
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.splitItems
import com.kzhovn.todoapp.recurrence.RecurrencePreset
import com.kzhovn.todoapp.recurrence.RecurrenceSelection
import com.kzhovn.todoapp.recurrence.recurrenceSelectionFromTask
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.data.moveOutFolderId
import com.kzhovn.todoapp.repository.InheritedField
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.changedInheritedFields
import kotlinx.coroutines.launch

// The task editor's state and everything it does to the data; TaskEditActivity is just the screen.
class TaskEditViewModel(
    private val repository: TaskRepository,
    private val contextRepository: ContextRepository,
    val taskId: Long = 0L,
    initialType: TaskType = TaskType.TASK,
    asTemplate: Boolean = false // "+ New template"
) : ViewModel() {
    val isNew get() = taskId == 0L

    var task by mutableStateOf(Task(id = taskId, title = "", type = initialType, isTemplate = asTemplate))
    // For an existing task, Save stays disabled until it has loaded: a tap before that would commit
    // the empty placeholder, wiping the task's title and every other field.
    var isLoaded by mutableStateOf(isNew)
        private set
    var recurrence by mutableStateOf(RecurrenceSelection(RecurrencePreset.NONE))
    var contextIds by mutableStateOf<Set<Long>>(emptySet())
    var dependencyIds by mutableStateOf<Set<Long>>(emptySet())

    // A new task has no id yet: its related tasks (and a new checklist's items) wait here, linked on save.
    var pendingSubtasks by mutableStateOf<List<String>>(emptyList())
    var pendingChildIds by mutableStateOf<Set<Long>>(emptySet())
    var pendingDependentIds by mutableStateOf<Set<Long>>(emptySet())

    var allTasks by mutableStateOf<List<Task>>(emptyList())
        private set
    var allContexts by mutableStateOf<List<TaskContext>>(emptyList())
        private set
    var dependencyEdges by mutableStateOf<List<TaskDependency>>(emptyList())
        private set
    val allById by derivedStateOf { allTasks.associateBy { it.id } }
    val folders by derivedStateOf { allTasks.filter { it.type == TaskType.FOLDER } }
    val folderColors by derivedStateOf { folderColors(allTasks) }
    val subtaskCounts by derivedStateOf { subtaskCounts(allTasks) }

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
        // A template's own tasks aren't in the lists' reads (see Templates.kt).
        allTasks = if (task.isTemplate) repository.getEverything() else repository.getAllTasks()
        template = if (task.isTemplate || isNew) null else task.fromTemplateId?.let { repository.getTask(it) }?.takeIf { it.isTemplate }
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
        val (overriding, fields) = repository.overridingSubtasks(taskId, changed)
        return if (overriding.isEmpty()) null else Question.UpdateSubtasks(overriding, fields)
    }

    // The task as it will be stored (see Task.typeRule). A folder can't be completed, so leaving it as
    // someone's dependency would block that task forever. It keeps contexts (children inherit them).
    private fun taskToSave(): Pair<Task, Set<Long>> =
        if (task.type == TaskType.FOLDER) {
            task.typeRule() to emptySet()
        } else {
            val (recurrenceType, recurrenceRule) = recurrenceToSave(recurrence, task)
            task.copy(recurrenceType = recurrenceType, recurrenceRule = recurrenceRule) to dependencyIds
        }

    // Saves the task with its contexts, dependencies and pending relations, all in one transaction.
    // Only what was edited is written (see write), sets included, so a change made meanwhile elsewhere
    // isn't undone. clearOn/fields: subtasks to make follow this task again (see Question.UpdateSubtasks).
    // Afterwards the stored task is the baseline for isDirty, since an auto-save keeps the editor open.
    fun saveEdits(clearOn: List<Task> = emptyList(), fields: Set<InheritedField> = emptySet(), onSaved: suspend (savedId: Long, saved: Task) -> Unit) {
        val (toSave, dependencies) = taskToSave()
        val contexts = contextIds
        val dependenciesChanged = isNew || dependencies != originalDependencyIds
        val contextsChanged = isNew || contexts != originalContextIds
        viewModelScope.launch {
            val (savedId, stored) = repository.atomically {
                val (savedId, stored) = write(toSave)
                if (stored != null) {
                    if (dependenciesChanged) repository.setDependencies(savedId, dependencies)
                    if (contextsChanged) contextRepository.setTaskContexts(savedId, contexts)
                    repository.clearInherited(clearOn, fields)
                    // A new checklist's items go in as items (see checklistItem); a task's as subtasks.
                    pendingSubtasks.forEach { if (toSave.type == TaskType.CHECKLIST) repository.addItems(savedId, it) else repository.addSubtask(savedId, it) }
                    pendingChildIds.forEach { repository.reparent(it, savedId) }
                    pendingDependentIds.forEach { repository.addDependency(it, savedId) }
                }
                savedId to stored
            }
            if (stored != null) {
                task = stored
                markSaved(stored)
            }
            onSaved(savedId, stored ?: toSave)
        }
    }

    fun save(task: Task, onSaved: suspend (Long) -> Unit) {
        viewModelScope.launch { onSaved(write(task).first) }
    }

    // A new task is created. An existing one gets only the fields edited here (mergeEdit), so a completion,
    // timer or sync that landed while the editor was open stays. Returns the id and what's stored now
    // (null: deleted meanwhile, so nothing is written).
    private suspend fun write(task: Task): Pair<Long, Task?> {
        if (task.id == 0L) return repository.createTask(task).let { it to repository.getTask(it) }
        val current = repository.getTask(task.id) ?: return task.id to null
        repository.updateTask(mergeEdit(original ?: current, task, current))
        return task.id to repository.getTask(task.id)
    }

    // New related tasks go in this task's folder, as related work usually belongs together.
    fun folderId(): Long? = folderOf(task, allById)

    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); refresh() }

    // Ticking one with open subtasks asks first (completeDecision: its id and how many), as the lists do.
    var completeDecision by mutableStateOf<Pair<Long, CompleteQuestion>?>(null)

    fun toggleComplete(id: Long) = act {
        val question = repository.completeQuestion(id)
        if (question != null) completeDecision = id to question else repository.toggleComplete(id, System.currentTimeMillis())
    }

    fun decideComplete(moveOut: Boolean) {
        val id = completeDecision?.first ?: return
        completeDecision = null
        act { repository.completeChoosing(id, moveOut, System.currentTimeMillis()) }
    }

    fun deleteItem(item: Task) = act { repository.deleteTask(item) }

    // "milk, eggs" adds two.
    fun addItems(text: String) {
        if (isNew) pendingSubtasks = pendingSubtasks + splitItems(text) else act { repository.addItems(taskId, text) }
    }

    fun uncheckAll() = act { repository.uncheckAll(taskId) }

    // The template this list was made from (still there), for "From template Packing · Update template…".
    var template by mutableStateOf<Task?>(null)
        private set
    var templateChanges by mutableStateOf<List<TemplateChange>?>(null) // the open review's lines

    fun saveAsTemplate(onSaved: () -> Unit) = act { repository.saveAsTemplate(taskId); onSaved() }
    fun useTemplate(onMade: (Long) -> Unit) = viewModelScope.launch { repository.useTemplate(taskId, System.currentTimeMillis())?.let(onMade) }
    fun reviewTemplateChanges() = viewModelScope.launch { templateChanges = repository.templateChanges(taskId) }
    fun updateTemplate(chosen: Set<String>, oneOff: Set<String>) { templateChanges = null; act { repository.updateTemplate(taskId, chosen, oneOff) } }

    // A section's own: its items into it, rename, check all, uncheck all (deleting is deleteItem).
    fun addItemsTo(sectionId: Long, text: String) = act { repository.addItems(sectionId, text) }
    fun addSection(title: String) = act { if (title.isNotBlank()) repository.addSection(taskId, title) }
    fun renameSection(section: Task, title: String) = act { if (title.isNotBlank()) repository.updateTask(section.copy(title = title.trim())) }
    fun checkAllIn(sectionId: Long) = act { repository.checkAll(sectionId) }
    fun uncheckAllIn(sectionId: Long) = act { repository.uncheckAll(sectionId) }

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

    // An event picked in Related (its item made the first time): after it, it's waited on like a prerequisite (saved
    // with the task); before it, the event waits on this task, as a picked dependent does.
    fun linkEvent(event: CalendarEvent, before: Boolean) = act {
        val item = repository.eventItem(event, System.currentTimeMillis())
        when {
            !before -> dependencyIds = dependencyIds + item.id
            isNew -> pendingDependentIds = pendingDependentIds + item.id
            else -> repository.addDependency(item.id, taskId)
        }
    }

    // A subtask's "move out": straight to the folder this task is in.
    fun moveOut(subtaskId: Long) = act { repository.reparent(subtaskId, moveOutFolderId(task, allById)) }

    fun moveSubtask(id: Long, anchorId: Long, after: Boolean) = act { repository.moveNextTo(id, anchorId, after) }

    fun createFolder(name: String) = act { task = task.copy(parentId = repository.createTask(Task(type = TaskType.FOLDER, title = name))) }

    suspend fun descendantCount(): Int = if (isNew) 0 else repository.countDescendants(taskId)

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.deleteTask(task); onDone() }
}
