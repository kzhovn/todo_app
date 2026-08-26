package com.kzhovn.todoapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

enum class TaskListMode { DOING, ACTIVE, ALL }

class TaskListViewModel(
    private val repository: TaskRepository,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private val _subtaskCounts = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val subtaskCounts: StateFlow<Map<Long, Pair<Int, Int>>> = _subtaskCounts

    fun load(mode: TaskListMode) {
        viewModelScope.launch {
            val now = clock()
            val all = refreshSubtaskCounts()
            _tasks.value = when (mode) {
                TaskListMode.ALL -> all
                TaskListMode.ACTIVE -> repository.getActiveTasks(now, minuteOfDay(now), dayOfWeekMask(now))
                TaskListMode.DOING -> repository.getActiveTasks(now, minuteOfDay(now), dayOfWeekMask(now))
                    .filter { it.isStarred || (it.dueDate != null && it.dueDate - now < TWO_DAYS_MILLIS) }
            }
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            refreshSubtaskCounts()
            _tasks.value = repository.search(query)
        }
    }

    private suspend fun refreshSubtaskCounts(): List<Task> {
        val all = repository.getAllTasks()
        _subtaskCounts.value = subtaskCounts(all)
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

    private fun minuteOfDay(epochMillis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun dayOfWeekMask(epochMillis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
    }

    companion object {
        private const val TWO_DAYS_MILLIS = 2 * 24 * 60 * 60 * 1000L
    }
}
