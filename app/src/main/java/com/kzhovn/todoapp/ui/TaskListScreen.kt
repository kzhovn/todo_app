package com.kzhovn.todoapp.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kzhovn.todoapp.data.Task

@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onCheck: (Long) -> Unit,
    onStar: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    LazyColumn {
        items(tasks, key = { it.id }) { task ->
            TaskRow(task, onCheck, onStar, onDelete)
        }
    }
}

@Composable
private fun TaskRow(task: Task, onCheck: (Long) -> Unit, onStar: (Long) -> Unit, onDelete: (Long) -> Unit) {
    Row {
        Checkbox(checked = task.isComplete, onCheckedChange = { onCheck(task.id) })
        Text(task.title)
        IconButton(onClick = { onStar(task.id) }) {
            Icon(if (task.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Star")
        }
        IconButton(onClick = { onDelete(task.id) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}
