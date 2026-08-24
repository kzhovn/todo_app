package com.kzhovn.todoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
            var selectedMode by remember { mutableStateOf(TaskListMode.DOING) }
            LaunchedEffect(selectedMode) { viewModel.load(selectedMode) }

            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) {
                Column {
                    TabRow(selectedTabIndex = TaskListMode.entries.indexOf(selectedMode)) {
                        TaskListMode.entries.forEach { mode ->
                            Tab(
                                selected = mode == selectedMode,
                                onClick = { selectedMode = mode },
                                text = { Text(mode.name) }
                            )
                        }
                    }
                    TaskListScreen(
                        viewModel = viewModel,
                        onCheck = { viewModel.markComplete(it, selectedMode) },
                        onStar = { viewModel.toggleStar(it, selectedMode) },
                        onDelete = { taskId ->
                            viewModel.deleteWithUndo(taskId, selectedMode) { deletedTask ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar("Task deleted", actionLabel = "Undo")
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete(deletedTask, selectedMode)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
