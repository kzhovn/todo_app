package com.kzhovn.todoapp.ui

import com.kzhovn.todoapp.data.subtaskCounts
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import com.kzhovn.todoapp.repository.urgentFirst
import com.kzhovn.todoapp.data.stalledProjects
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.minuteOfDay
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
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private sealed interface Shown {
        data class Tab(val mode: TaskListMode) : Shown
        data class Search(val query: String, val filters: SearchFilters) : Shown
    }
    private var shown: Shown = Shown.Tab(TaskListMode.DOING)
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

    private val _contextsByTaskId = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val contextsByTaskId: StateFlow<Map<Long, Set<Long>>> = _contextsByTaskId

    private val _allContexts = MutableStateFlow<Map<Long, TaskContext>>(emptyMap())
    val allContexts: StateFlow<Map<Long, TaskContext>> = _allContexts

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
                is Shown.Search -> { refreshSubtaskCounts(); _tasks.value = repository.search(s.query, s.filters) }
            }
        }
    }

    private suspend fun active(all: List<Task>, now: Long) =
        repository.getActiveTasksFrom(all, _contextsByTaskId.value, now, minuteOfDay(now), dayOfWeekMask(now))

    private suspend fun loadTab(mode: TaskListMode) {
        val now = clock()
        repository.purgeExpired(now)
        val all = refreshSubtaskCounts()
        _tasks.value = when (mode) {
            TaskListMode.ALL -> all
            // Overdue and due today first, like Doing (each folder section keeps that order).
            TaskListMode.ACTIVE -> urgentFirst(active(all, now), now, _allById.value, _contextsByTaskId.value)
            TaskListMode.DOING -> filterDoing(active(all, now), now, _allById.value, _contextsByTaskId.value)
        }
    }

    // Projects whose subtasks are all done, waiting on "complete it or add the next step".
    private val _stalledProjects = MutableStateFlow<List<Task>>(emptyList())
    val stalledProjects: StateFlow<List<Task>> = _stalledProjects

    fun completeProject(projectId: Long) {
        viewModelScope.launch {
            repository.markComplete(projectId, clock())
            reload()
        }
    }

    fun addSubtask(parentId: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createTask(Task(title = title, parentId = parentId))
            reload()
        }
    }

    fun move(taskId: Long, anchorId: Long, after: Boolean) {
        viewModelScope.launch {
            repository.moveNextTo(taskId, anchorId, after)
            reload()
        }
    }

    private suspend fun refreshSubtaskCounts(): List<Task> {
        repository.ensureFolderColors()
        val all = repository.getAllTasks()
        _stalledProjects.value = stalledProjects(all)
        _subtaskCounts.value = subtaskCounts(all)
        _allById.value = all.associateBy { it.id }
        _contextsByTaskId.value = repository.getAllTaskContexts()
        _allContexts.value = contextRepository.getAllContexts().associateBy { it.id }
        return all
    }

    fun toggleStar(taskId: Long) {
        viewModelScope.launch {
            repository.toggleStar(taskId)
            reload()
        }
    }

    fun snooze(taskId: Long, until: Long) {
        viewModelScope.launch {
            repository.snooze(taskId, until, clock())
            reload()
        }
    }

    fun reparent(taskId: Long, newParentId: Long?) {
        viewModelScope.launch {
            repository.reparent(taskId, newParentId)
            reload()
        }
    }

    fun requestComplete(taskId: Long, onNeedsDecision: (Long, Int) -> Unit) {
        viewModelScope.launch {
            val task = repository.getTask(taskId) ?: return@launch
            if (task.isComplete) {
                repository.toggleComplete(taskId, clock())
                reload()
                return@launch
            }
            val activeDescendants = repository.countActiveDescendants(taskId)
            if (activeDescendants > 0) {
                onNeedsDecision(taskId, activeDescendants)
            } else {
                repository.toggleComplete(taskId, clock())
                reload()
            }
        }
    }

    fun completeWithSubtasks(taskId: Long) {
        viewModelScope.launch {
            repository.completeWithDescendants(taskId, clock())
            reload()
        }
    }

    fun completeAndPromoteSubtasks(taskId: Long) {
        viewModelScope.launch {
            repository.promoteChildrenToTopLevel(taskId)
            repository.toggleComplete(taskId, clock())
            reload()
        }
    }

}
