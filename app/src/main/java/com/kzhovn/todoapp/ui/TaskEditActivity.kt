package com.kzhovn.todoapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import com.kzhovn.todoapp.TodoApp
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.ui.theme.LedgerTheme
import com.kzhovn.todoapp.widget.TodoWidget

class TaskEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        setContent {
            LedgerTheme {
            val viewModel = remember { TaskEditViewModel(repository) }
            var task by remember { mutableStateOf(Task(id = taskId, title = "")) }
            // For an existing task, Save must stay disabled until the real task data has
            // loaded — otherwise a tap before the load completes commits this empty
            // placeholder, wiping the task's title and every other field.
            var isLoaded by remember { mutableStateOf(taskId == 0L) }
            LaunchedEffect(taskId) {
                if (taskId != 0L) {
                    viewModel.load(taskId)?.let { task = it }
                    isLoaded = true
                }
            }
            Column {
                OutlinedTextField(value = task.title, onValueChange = { task = task.copy(title = it) })
                Text("Starred")
                Switch(checked = task.isStarred, onCheckedChange = { task = task.copy(isStarred = it) })
                Button(
                    onClick = {
                        viewModel.save(task) {
                            TodoWidget().updateAll(applicationContext)
                            finish()
                        }
                    },
                    enabled = isLoaded && task.title.isNotBlank()
                ) {
                    Text("Save")
                }
            }
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
