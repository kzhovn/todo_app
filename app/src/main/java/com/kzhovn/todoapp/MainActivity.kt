package com.kzhovn.todoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kzhovn.todoapp.ui.TaskListMode
import com.kzhovn.todoapp.ui.TaskListScreen
import com.kzhovn.todoapp.ui.TaskListViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TodoApp).repository
        setContent {
            val viewModel = remember { TaskListViewModel(repository) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { viewModel.load(TaskListMode.DOING) }

            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) {
                TaskListScreen(
                    viewModel = viewModel,
                    onCheck = { viewModel.markComplete(it, TaskListMode.DOING) },
                    onStar = { viewModel.toggleStar(it, TaskListMode.DOING) },
                    onDelete = { taskId ->
                        viewModel.deleteWithUndo(taskId, TaskListMode.DOING) { deletedTask ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar("Task deleted", actionLabel = "Undo")
                                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                    viewModel.undoDelete(deletedTask, TaskListMode.DOING)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
