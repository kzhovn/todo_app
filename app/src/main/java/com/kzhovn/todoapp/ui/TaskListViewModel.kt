package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.subtaskCounts
import com.kzhovn.todoapp.data.dueOutsideMode
import com.kzhovn.todoapp.data.DueDates
import com.kzhovn.todoapp.data.CompleteQuestion
import com.kzhovn.todoapp.data.inMode
import com.kzhovn.todoapp.data.modeFolder
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.repository.computeActiveIgnoringContexts
import com.kzhovn.todoapp.data.stalledProjects
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.data.waitingToCheck
import com.kzhovn.todoapp.data.waitingBlocks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TaskListMode { DOING, ACTIVE, ALL }

// What the list shows: a tab, or search results. It reloads itself on any data change (`changes`,
// from TodoApp.listInputChanges), so actions here and edits elsewhere (another screen, the widget, a
// sync) all show up without each caller reloading.
class TaskListViewModel(
    private val repository: TaskRepository,
    private val contextRepository: ContextRepository,
    changes: Flow<Unit> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
    // Folder mode's folder id (AppSettings); Doing and Active keep to it.
    private val modeFolderId: () -> Long? = { null }
) : ViewModel() {
    private sealed interface Shown {
        data class Tab(val mode: TaskListMode) : Shown
        data class Search(val query: String, val filters: SearchFilters) : Shown
    }
    private var shown: Shown = Shown.Tab(TaskListMode.DOING)

    // While searching, what for (the rows show the line of notes a task matched on).
    val searchQuery: String? get() = (shown as? Shown.Search)?.query
    private var reloading: Job? = null

    init {
        viewModelScope.launch { changes.collect { reload() } }
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private val _subtaskCounts = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val subtaskCounts: StateFlow<Map<Long, Pair<Int, Int>>> = _subtaskCounts

    private val _allById = MutableStateFlow<Map<Long, Task>>(emptyMap())
    val allById: StateFlow<Map<Long, Task>> = _allById
    private val _dues = MutableStateFlow(DueDates(emptyList(), emptyList()))
    val dues: StateFlow<DueDates> = _dues
    private val _workable = MutableStateFlow<Set<Long>>(emptySet())
    val workable: StateFlow<Set<Long>> = _workable // see dimmedInTree

    private val _contextsByTaskId = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val contextsByTaskId: StateFlow<Map<Long, Set<Long>>> = _contextsByTaskId

    private val _allContexts = MutableStateFlow<Map<Long, TaskContext>>(emptyMap())
    val allContexts: StateFlow<Map<Long, TaskContext>> = _allContexts

    // Folder mode's folder, if it still exists.
    private val _mode = MutableStateFlow<Task?>(null)
    val mode: StateFlow<Task?> = _mode

    // Doing in a mode: what's due today or overdue outside it (the line under the list).
    private val _dueOutside = MutableStateFlow<List<Task>>(emptyList())
    val dueOutside: StateFlow<List<Task>> = _dueOutside

    // Doing's quiet Waiting section: waiting items whose check-in has come round (see waitingToCheck).
    private val _waiting = MutableStateFlow<List<Task>>(emptyList())
    val waiting: StateFlow<List<Task>> = _waiting
    // How many open tasks each of those blocks.
    private val _waitingBlocks = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val waitingBlocks: StateFlow<Map<Long, Int>> = _waitingBlocks

    fun stillWaiting(taskId: Long) = act { repository.stillWaiting(taskId, clock()) }

    // A change, then the list again. (Room's invalidation also reloads it; the explicit one keeps a list
    // without that feed, as in the tests, current.)
    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); reload() }

    fun load(mode: TaskListMode) {
        shown = Shown.Tab(mode)
        reload()
    }

    fun search(query: String, filters: SearchFilters = SearchFilters()) {
        shown = Shown.Search(query, filters)
        reload()
    }

    // A newer reload replaces one still running, so a slow load can't land after a newer one.
    fun reload() {
        reloading?.cancel()
        reloading = viewModelScope.launch {
            when (val s = shown) {
                is Shown.Tab -> loadTab(s.mode)
                is Shown.Search -> { refreshSubtaskCounts(repository.listedTasks(clock())); _tasks.value = repository.search(s.query, s.filters) }
            }
        }
    }

    private suspend fun active(all: List<Task>, now: Long) = repository.getActiveTasksFrom(all, _contextsByTaskId.value, now)

    private suspend fun loadTab(mode: TaskListMode) {
        val now = clock()
        val all = refreshSubtaskCounts(repository.listedTasks(now))
        val byId = _allById.value
        val folder = _mode.value?.id
        _dueOutside.value = emptyList()
        _waiting.value = if (mode == TaskListMode.DOING) waitingToCheck(all, now).filter { inMode(it, folder, byId) } else emptyList()
        if (_waiting.value.isNotEmpty()) _waitingBlocks.value = waitingBlocks(_waiting.value, repository.getAllDependencyEdges(), byId)
        _tasks.value = when (mode) {
            // The tree roots itself at the mode's folder (OutlinerScreen's rootId).
            TaskListMode.ALL -> all
            // Overdue and due today first, like Doing (each folder section keeps that order).
            TaskListMode.ACTIVE -> urgentFirst(active(all, now).filter { inMode(it, folder, byId) }, now, _dues.value)
            TaskListMode.DOING -> {
                val doing = filterDoing(active(all, now), now, _dues.value)
                _dueOutside.value = dueOutsideMode(doing, folder, byId, now, _dues.value)
                doing.filter { inMode(it, folder, byId) }
            }
        }
    }

    // Projects whose subtasks are all done, waiting on "complete it or add the next step".
    private val _stalledProjects = MutableStateFlow<List<Task>>(emptyList())
    val stalledProjects: StateFlow<List<Task>> = _stalledProjects

    fun completeProject(projectId: Long) = act { repository.markComplete(projectId, clock()) }

    fun move(taskId: Long, anchorId: Long, after: Boolean) = act { repository.moveNextTo(taskId, anchorId, after) }

    private suspend fun refreshSubtaskCounts(all: List<Task>): List<Task> {
        _stalledProjects.value = stalledProjects(all)
        _subtaskCounts.value = subtaskCounts(all)
        _allById.value = all.associateBy { it.id }
        val edges = repository.getAllDependencyEdges()
        _dues.value = DueDates(all, edges)
        _workable.value = computeActiveIgnoringContexts(all, edges, clock()).map { it.id }.toSet()
        _mode.value = modeFolder(modeFolderId(), _allById.value)
        _contextsByTaskId.value = repository.getAllTaskContexts()
        _allContexts.value = contextRepository.getAllContexts().associateBy { it.id }
        return all
    }

    fun toggleStar(taskId: Long) = act { repository.toggleStar(taskId) }

    fun snooze(taskId: Long, until: Long) = act { repository.snooze(taskId, until, clock()) }

    fun reparent(taskId: Long, newParentId: Long?) = act { repository.reparent(taskId, newParentId) }

    // Open subtasks are asked about first (onNeedsDecision); otherwise it's ticked, or unticked, at once.
    fun requestComplete(taskId: Long, onNeedsDecision: (Long, CompleteQuestion) -> Unit) {
        viewModelScope.launch {
            val question = repository.completeQuestion(taskId)
            if (question != null) onNeedsDecision(taskId, question) else act { repository.toggleComplete(taskId, clock()) }
        }
    }

    // The answer to that question (planCompleteChoosing).
    fun completeChoosing(taskId: Long, moveOut: Boolean) = act { repository.completeChoosing(taskId, moveOut, clock()) }
}
