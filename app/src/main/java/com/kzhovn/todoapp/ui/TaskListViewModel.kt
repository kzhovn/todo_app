package com.kzhovn.todoapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.resolveEffective
import com.kzhovn.todoapp.repository.ContextRepository
import com.kzhovn.todoapp.repository.TaskRepository
import com.kzhovn.todoapp.repository.dayOfWeekMask
import com.kzhovn.todoapp.repository.filterDoing
import com.kzhovn.todoapp.repository.minuteOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TaskListMode { DOING, ACTIVE, ALL }

class TaskListViewModel(
    private val repository: TaskRepository,
    private val contextRepository: ContextRepository,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

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
        viewModelScope.launch {
            val now = clock()
            val all = refreshSubtaskCounts()
            _tasks.value = when (mode) {
                TaskListMode.ALL -> all
                TaskListMode.ACTIVE -> repository.getActiveTasks(now, minuteOfDay(now), dayOfWeekMask(now))
                TaskListMode.DOING -> filterDoing(
                    repository.getActiveTasks(now, minuteOfDay(now), dayOfWeekMask(now)),
                    now
                ) { resolveEffective(it, _allById.value, _contextsByTaskId.value).effectiveDueDate }
            }
        }
    }

    fun search(query: String, filters: SearchFilters = SearchFilters()) {
        viewModelScope.launch {
            refreshSubtaskCounts()
            _tasks.value = repository.search(query, filters)
        }
    }

    private suspend fun refreshSubtaskCounts(): List<Task> {
        val all = repository.getAllTasks()
        _subtaskCounts.value = subtaskCounts(all)
        _allById.value = all.associateBy { it.id }
        _contextsByTaskId.value = repository.getAllTaskContexts()
        _allContexts.value = contextRepository.getAllContexts().associateBy { it.id }
        return all
    }

    fun toggleStar(taskId: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.toggleStar(taskId)
            load(mode)
        }
    }

    fun markComplete(taskId: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.markComplete(taskId, clock())
            load(mode)
        }
    }

    fun toggleComplete(taskId: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.toggleComplete(taskId, clock())
            load(mode)
        }
    }

    fun snooze(taskId: Long, durationMillis: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.snooze(taskId, durationMillis, clock())
            load(mode)
        }
    }

    fun reparent(taskId: Long, newParentId: Long?, mode: TaskListMode) {
        viewModelScope.launch {
            repository.reparent(taskId, newParentId)
            load(mode)
        }
    }

    fun requestComplete(taskId: Long, mode: TaskListMode, onNeedsDecision: (Long, Int) -> Unit) {
        viewModelScope.launch {
            val task = repository.getTask(taskId) ?: return@launch
            if (task.isComplete) {
                repository.toggleComplete(taskId, clock())
                load(mode)
                return@launch
            }
            val activeDescendants = repository.countActiveDescendants(taskId)
            if (activeDescendants > 0) {
                onNeedsDecision(taskId, activeDescendants)
            } else {
                repository.toggleComplete(taskId, clock())
                load(mode)
            }
        }
    }

    fun completeWithSubtasks(taskId: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.completeWithDescendants(taskId, clock())
            load(mode)
        }
    }

    fun completeAndPromoteSubtasks(taskId: Long, mode: TaskListMode) {
        viewModelScope.launch {
            repository.promoteChildrenToTopLevel(taskId)
            repository.toggleComplete(taskId, clock())
            load(mode)
        }
    }

}
