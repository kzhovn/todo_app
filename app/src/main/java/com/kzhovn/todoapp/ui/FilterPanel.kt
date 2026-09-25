package com.kzhovn.todoapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kzhovn.todoapp.data.SearchFilters
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskContext

// One wrapping row of compact chips. Folder and context are dropdowns rather than a pill per option,
// so the panel stays a couple of lines tall however many folders/contexts exist.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(
    filters: SearchFilters,
    folders: List<Task>,
    contexts: List<TaskContext>,
    onFiltersChange: (SearchFilters) -> Unit
) {
    val activity = LocalContext.current as android.app.Activity
    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SelectablePill(label = "Starred", selected = filters.starredOnly, fontSize = 13.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) {
            onFiltersChange(filters.copy(starredOnly = !filters.starredOnly))
        }
        SelectablePill(label = "Completed", selected = filters.includeCompleted, fontSize = 13.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp) {
            onFiltersChange(filters.copy(includeCompleted = !filters.includeCompleted))
        }
        DropdownChip(
            label = "Folder",
            icon = Icons.Filled.Folder,
            selected = folders.firstOrNull { it.id == filters.folderId }?.title,
            options = folders.sortedBy { it.title.lowercase() }.map { it.id to it.title },
            onPick = { onFiltersChange(filters.copy(folderId = it)) }
        )
        DropdownChip(
            label = "Context",
            icon = Icons.Filled.AlternateEmail,
            selected = contexts.firstOrNull { it.id == filters.contextId }?.name,
            options = contexts.sortedBy { it.name.lowercase() }.map { it.id to it.name },
            onPick = { onFiltersChange(filters.copy(contextId = it)) }
        )
        PropertyChip(
            label = "Due after",
            valueText = filters.dueAfter?.let(::formatChipDate),
            icon = Icons.Filled.Event,
            onClick = { pickDate(activity, filters.dueAfter, withTime = false) { onFiltersChange(filters.copy(dueAfter = it)) } },
            onClear = { onFiltersChange(filters.copy(dueAfter = null)) }
        )
        PropertyChip(
            label = "Due before",
            valueText = filters.dueBefore?.let(::formatChipDate),
            icon = Icons.Filled.Flag,
            onClick = { pickDate(activity, filters.dueBefore, withTime = false) { onFiltersChange(filters.copy(dueBefore = it)) } },
            onClear = { onFiltersChange(filters.copy(dueBefore = null)) }
        )
    }
}

// A chip showing the current choice ("Folder" when none); tapping opens a menu with "Any" first.
@Composable
private fun DropdownChip(label: String, icon: ImageVector, selected: String?, options: List<Pair<Long, String>>, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PropertyChip(
            label = label,
            valueText = selected,
            icon = icon,
            onClick = { open = true },
            onClear = { onPick(null) },
            showLabelWhenSet = false
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { open = false; onPick(null) })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
