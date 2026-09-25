package com.kzhovn.todoapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.ui.theme.LedgerMuted

@Composable
fun FilterPanel(
    filters: SearchFilters,
    folders: List<Task>,
    contexts: List<TaskContext>,
    onFiltersChange: (SearchFilters) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row {
            SelectablePill(label = "Starred", selected = filters.starredOnly, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) {
                onFiltersChange(filters.copy(starredOnly = !filters.starredOnly))
            }
            Spacer(Modifier.width(6.dp))
            SelectablePill(label = "Completed", selected = filters.includeCompleted, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) {
                onFiltersChange(filters.copy(includeCompleted = !filters.includeCompleted))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Folder:", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 4.dp))
            SelectablePill(label = "Any", selected = filters.folderId == null, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) { onFiltersChange(filters.copy(folderId = null)) }
            folders.forEach { f ->
                Spacer(Modifier.width(6.dp))
                SelectablePill(label = f.title, selected = filters.folderId == f.id, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) { onFiltersChange(filters.copy(folderId = f.id)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Context:", fontSize = 12.sp, color = LedgerMuted, modifier = Modifier.padding(end = 4.dp))
            SelectablePill(label = "Any", selected = filters.contextId == null, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) { onFiltersChange(filters.copy(contextId = null)) }
            contexts.forEach { c ->
                Spacer(Modifier.width(6.dp))
                SelectablePill(label = c.name, selected = filters.contextId == c.id, fontSize = 12.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) { onFiltersChange(filters.copy(contextId = c.id)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            val activity = LocalContext.current as android.app.Activity
            PropertyChip(
                label = "Due after",
                valueText = filters.dueAfter?.let(::formatChipDate),
                icon = Icons.Filled.Event,
                onClick = { pickDate(activity, filters.dueAfter, withTime = false) { onFiltersChange(filters.copy(dueAfter = it)) } },
                onClear = { onFiltersChange(filters.copy(dueAfter = null)) }
            )
            Spacer(Modifier.width(8.dp))
            PropertyChip(
                label = "Due before",
                valueText = filters.dueBefore?.let(::formatChipDate),
                icon = Icons.Filled.Flag,
                onClick = { pickDate(activity, filters.dueBefore, withTime = false) { onFiltersChange(filters.copy(dueBefore = it)) } },
                onClear = { onFiltersChange(filters.copy(dueBefore = null)) }
            )
        }
    }
}
