package com.kzhovn.todoapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.TaskRepository
import kotlinx.coroutines.launch

class TaskEditViewModel(private val repository: TaskRepository) : ViewModel() {

    suspend fun load(taskId: Long): Task? = repository.getTask(taskId)

    fun save(task: Task, onSaved: () -> Unit) {
        viewModelScope.launch {
            if (task.id == 0L) repository.createTask(task) else repository.updateTask(task)
            onSaved()
        }
    }

    fun setSequential(folderId: Long, sequential: Boolean) {
        viewModelScope.launch { repository.setSequential(folderId, sequential) }
    }
}
